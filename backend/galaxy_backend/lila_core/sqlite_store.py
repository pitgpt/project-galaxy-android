"""Encrypted, owner-scoped SQLite persistence for Lila conversations and memories.

Sensitive message/memory content is stored as AES-GCM ciphertext and raw owner,
conversation, and memory identifiers are replaced by HMAC-derived indexes.
The retrieval index stores only per-owner HMAC token digests, never plaintext
terms; it reveals term equality/frequency metadata within one owner's index.
Roles, sequence numbers, timestamps, and schema metadata remain visible.
The 32-byte master key is supplied by the application composition root and is
never persisted by this adapter.
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import re
import sqlite3
import stat
import time
from pathlib import Path
from threading import Lock
from typing import Sequence

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .contracts import CoreTurnResponse
from .memory import (
    ConversationMessage,
    IdempotencyKeyConflict,
    IdempotentTurnInProgress,
    MemorySnippet,
    StoredConversationMessage,
)


class ProviderAlreadyExists(RuntimeError):
    """An owner already has a provider with this identifier."""


class ProviderLimitExceeded(RuntimeError):
    """The owner has reached the provider catalog limit."""


class ProviderNotFound(RuntimeError):
    """The selected provider does not belong to the authenticated owner."""


class ProviderDisabled(RuntimeError):
    """The selected provider is disabled."""


class LilaStoreIntegrityError(RuntimeError):
    """Stored Lila data failed authenticated decryption or validation."""


class SqliteLilaStore:
    """One-file encrypted store implementing history and memory reader/writers.

    SQLite WAL plus FULL synchronous commits provide local durability for one
    backend instance on a local persistent volume. Do not share this WAL database
    across replicas or network filesystems. Production must supply an absolute
    database path on a persistent volume and a stable key from a secret manager.
    This adapter does not make an ephemeral container filesystem durable.
    """

    _SCHEMA_VERSION = 7
    _NONCE_BYTES = 12
    _MEMORY_BACKFILL_BATCH = 128
    _MAX_MEMORY_CANDIDATES = 256
    _MAX_MEMORY_TERMS = 256
    _MAX_QUERY_TERMS = 64

    def __init__(self, database_path: str | Path, encryption_key: bytes) -> None:
        path = Path(database_path)
        if not path.is_absolute():
            raise ValueError("Lila database path must be absolute")
        if path.name in {"", ".", ".."}:
            raise ValueError("Lila database path must identify a file")
        if not isinstance(encryption_key, (bytes, bytearray)) or len(encryption_key) != 32:
            raise ValueError("Lila storage encryption key must contain exactly 32 bytes")

        master = bytes(encryption_key)
        encryption_subkey = hmac.new(master, b"lila-storage-encryption-v1", hashlib.sha256).digest()
        self._index_key = hmac.new(master, b"lila-storage-index-v1", hashlib.sha256).digest()
        self._cipher = AESGCM(encryption_subkey)
        self._path = path
        self._initialized = False
        self._init_lock = Lock()

    def __repr__(self) -> str:
        return f"{type(self).__name__}(database_path={str(self._path)!r})"

    async def initialize(self) -> None:
        await asyncio.to_thread(self._ensure_initialized_sync)

    def _ensure_initialized_sync(self) -> None:
        with self._init_lock:
            if self._initialized:
                return
            self._initialize_sync()
            self._initialized = True

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(str(self._path), timeout=5.0, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA busy_timeout=5000")
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA synchronous=FULL")
        connection.execute("PRAGMA secure_delete=ON")
        connection.execute("PRAGMA temp_store=MEMORY")
        return connection

    def _initialize_sync(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        directory_mode = stat.S_IMODE(self._path.parent.stat().st_mode)
        if directory_mode & 0o077:
            raise RuntimeError("Lila database directory must be private to the service account")
        flags = os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0)
        if self._path.is_symlink():
            raise RuntimeError("Lila database path must not be a symbolic link")
        descriptor = os.open(self._path, flags, 0o600)
        try:
            file_stat = os.fstat(descriptor)
            if not stat.S_ISREG(file_stat.st_mode) or file_stat.st_nlink != 1:
                raise RuntimeError("Lila database path must be a private regular file")
            os.fchmod(descriptor, 0o600)
        finally:
            os.close(descriptor)

        connection = self._connect()
        try:
            mode = connection.execute("PRAGMA journal_mode=WAL").fetchone()[0]
            if str(mode).lower() != "wal":
                raise RuntimeError("SQLite WAL mode is required for Lila persistence")
            connection.execute("BEGIN IMMEDIATE")
            try:
                version = int(connection.execute("PRAGMA user_version").fetchone()[0])
                if version == 0:
                    connection.execute(
                        """CREATE TABLE lila_messages (
                            owner_key TEXT NOT NULL,
                            conversation_key TEXT NOT NULL,
                            sequence INTEGER NOT NULL CHECK(sequence > 0),
                            role TEXT NOT NULL CHECK(role IN ('user', 'assistant')),
                            nonce BLOB NOT NULL CHECK(length(nonce) = 12),
                            ciphertext BLOB NOT NULL,
                            created_at_ms INTEGER NOT NULL,
                            PRIMARY KEY(owner_key, conversation_key, sequence)
                        ) WITHOUT ROWID"""
                    )
                    connection.execute(
                        """CREATE TABLE lila_meta (
                            singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                            key_check BLOB NOT NULL
                        )"""
                    )
                    connection.execute(
                        "INSERT INTO lila_meta(singleton, key_check) VALUES(1, ?)",
                        (self._key_check(),),
                    )
                    connection.execute(
                        """CREATE TABLE lila_memories (
                            owner_key TEXT NOT NULL,
                            memory_key TEXT NOT NULL,
                            nonce BLOB NOT NULL CHECK(length(nonce) = 12),
                            ciphertext BLOB NOT NULL,
                            created_at_ms INTEGER NOT NULL,
                            updated_at_ms INTEGER NOT NULL,
                            PRIMARY KEY(owner_key, memory_key)
                        ) WITHOUT ROWID"""
                    )
                    version = 1
                elif version not in {1, 2, 3, 4, 5, 6, self._SCHEMA_VERSION}:
                    raise RuntimeError("Unsupported Lila database schema version")
                else:
                    row = connection.execute(
                        "SELECT key_check FROM lila_meta WHERE singleton=1"
                    ).fetchone()
                    if row is None or not hmac.compare_digest(bytes(row[0]), self._key_check()):
                        raise LilaStoreIntegrityError("Lila storage key does not match the database")

                # Version 2 adds an owner-bound HMAC term index. Existing
                # encrypted memories are backfilled below in restartable batches.
                connection.execute(
                    """CREATE TABLE IF NOT EXISTS lila_memory_terms (
                        owner_key TEXT NOT NULL,
                        memory_key TEXT NOT NULL,
                        term_key TEXT NOT NULL,
                        PRIMARY KEY(owner_key, term_key, memory_key),
                        FOREIGN KEY(owner_key, memory_key)
                            REFERENCES lila_memories(owner_key, memory_key)
                            ON DELETE CASCADE
                    ) WITHOUT ROWID"""
                )
                connection.execute(
                    "CREATE INDEX IF NOT EXISTS idx_lila_memory_terms_owner_memory "
                    "ON lila_memory_terms(owner_key, memory_key)"
                )
                # Version 3 stores encrypted, replayable results for authenticated
                # idempotency keys. Raw keys, messages, and responses are never indexed.
                connection.execute(
                    """CREATE TABLE IF NOT EXISTS lila_turn_requests (
                        owner_key TEXT NOT NULL,
                        request_key TEXT NOT NULL,
                        conversation_key TEXT NOT NULL,
                        request_digest TEXT NOT NULL,
                        state TEXT NOT NULL CHECK(state IN ('pending', 'completed')),
                        response_nonce BLOB,
                        response_ciphertext BLOB,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        PRIMARY KEY(owner_key, request_key),
                        CHECK(
                            (state='pending' AND response_nonce IS NULL AND response_ciphertext IS NULL)
                            OR
                            (state='completed' AND response_nonce IS NOT NULL
                                AND length(response_nonce)=12 AND response_ciphertext IS NOT NULL)
                        )
                    ) WITHOUT ROWID"""
                )
                # Version 4 stores non-secret model identity separately from encrypted text.
                connection.execute(
                    """CREATE TABLE IF NOT EXISTS lila_turn_metadata (
                        owner_key TEXT NOT NULL,
                        conversation_key TEXT NOT NULL,
                        assistant_sequence INTEGER NOT NULL CHECK(assistant_sequence > 0),
                        provider_id TEXT CHECK(provider_id IS NULL OR length(provider_id) BETWEEN 1 AND 64),
                        model_id TEXT CHECK(model_id IS NULL OR length(model_id) BETWEEN 1 AND 256),
                        PRIMARY KEY(owner_key, conversation_key, assistant_sequence),
                        FOREIGN KEY(owner_key, conversation_key, assistant_sequence)
                            REFERENCES lila_messages(owner_key, conversation_key, sequence)
                            ON DELETE CASCADE,
                        CHECK(provider_id IS NOT NULL OR model_id IS NOT NULL)
                    ) WITHOUT ROWID"""
                )
                # Version 7 adds non-secret fallback identity/reason to assistant history metadata.
                # Existing rows remain valid with NULL fallback columns.
                metadata_columns = {
                    str(row["name"])
                    for row in connection.execute("PRAGMA table_info(lila_turn_metadata)").fetchall()
                }
                fallback_columns = (
                    (
                        "fallback_from_provider_id",
                        "TEXT CHECK(fallback_from_provider_id IS NULL OR length(fallback_from_provider_id) BETWEEN 1 AND 64)",
                    ),
                    (
                        "fallback_from_model_id",
                        "TEXT CHECK(fallback_from_model_id IS NULL OR length(fallback_from_model_id) BETWEEN 1 AND 256)",
                    ),
                    (
                        "fallback_reason",
                        "TEXT CHECK(fallback_reason IS NULL OR fallback_reason IN ('primary_connection_unavailable', 'primary_provider_unavailable'))",
                    ),
                )
                for column_name, declaration in fallback_columns:
                    if column_name not in metadata_columns:
                        connection.execute(
                            f"ALTER TABLE lila_turn_metadata ADD COLUMN {column_name} {declaration}"
                        )

                # Version 5 stores owner-managed provider configuration as authenticated ciphertext.
                # Provider IDs and owner IDs are represented only by HMAC-derived indexes.
                connection.execute(
                    """CREATE TABLE IF NOT EXISTS lila_providers (
                        owner_key TEXT NOT NULL,
                        provider_key TEXT NOT NULL,
                        nonce BLOB NOT NULL CHECK(length(nonce) = 12),
                        ciphertext BLOB NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        PRIMARY KEY(owner_key, provider_key)
                    ) WITHOUT ROWID"""
                )
                # Version 6 stores the selected provider/model per owner conversation.
                # The provider ID and model ID are authenticated ciphertext; indexes
                # contain only HMAC-derived owner/conversation/provider keys.
                connection.execute(
                    """CREATE TABLE IF NOT EXISTS lila_conversation_models (
                        owner_key TEXT NOT NULL,
                        conversation_key TEXT NOT NULL,
                        provider_key TEXT NOT NULL,
                        nonce BLOB NOT NULL CHECK(length(nonce) = 12),
                        ciphertext BLOB NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        PRIMARY KEY(owner_key, conversation_key)
                    ) WITHOUT ROWID"""
                )
                connection.execute(f"PRAGMA user_version={self._SCHEMA_VERSION}")
                connection.execute("COMMIT")
            except BaseException:
                connection.execute("ROLLBACK")
                raise
        finally:
            connection.close()

        self._backfill_memory_terms_sync()

    def _backfill_memory_terms_sync(self) -> None:
        """Index legacy encrypted memories in small, restartable transactions."""
        while True:
            connection = self._connect()
            try:
                connection.execute("BEGIN IMMEDIATE")
                try:
                    rows = connection.execute(
                        """SELECT m.owner_key, m.memory_key, m.nonce, m.ciphertext
                        FROM lila_memories AS m
                        WHERE NOT EXISTS (
                            SELECT 1 FROM lila_memory_terms AS t
                            WHERE t.owner_key=m.owner_key AND t.memory_key=m.memory_key
                        )
                        ORDER BY m.owner_key, m.memory_key
                        LIMIT ?""",
                        (self._MEMORY_BACKFILL_BATCH,),
                    ).fetchall()
                    if not rows:
                        connection.execute("COMMIT")
                        return

                    for row in rows:
                        owner_key = str(row["owner_key"])
                        memory_key = str(row["memory_key"])
                        memory = self._decode_memory(
                            owner_key, memory_key, row["nonce"], row["ciphertext"]
                        )
                        term_keys = self._term_keys(owner_key, memory.content)
                        indexed_terms = term_keys or ("",)
                        connection.executemany(
                            "INSERT OR IGNORE INTO lila_memory_terms(owner_key, memory_key, term_key) "
                            "VALUES(?, ?, ?)",
                            ((owner_key, memory_key, term_key) for term_key in indexed_terms),
                        )
                    connection.execute("COMMIT")
                except BaseException:
                    connection.execute("ROLLBACK")
                    raise
            finally:
                connection.close()

            if len(rows) < self._MEMORY_BACKFILL_BATCH:
                return


    def _owner_key(self, principal_id: str) -> str:
        if not isinstance(principal_id, str) or not principal_id.strip() or len(principal_id) > 256:
            raise ValueError("principal_id must be a non-empty authenticated subject")
        return hmac.new(self._index_key, b"owner\0" + principal_id.encode("utf-8"), hashlib.sha256).hexdigest()

    def _resource_key(self, kind: str, value: str, maximum: int) -> str:
        if not isinstance(value, str) or not value.strip() or len(value) > maximum:
            raise ValueError(f"{kind} must be a non-empty string within its supported length")
        return hmac.new(self._index_key, kind.encode("ascii") + b"\0" + value.encode("utf-8"), hashlib.sha256).hexdigest()

    def _key_check(self) -> bytes:
        return hmac.new(self._index_key, b"lila-store-key-check-v1", hashlib.sha256).digest()

    @staticmethod
    def _aad(kind: str, owner_key: str, resource_key: str, suffix: str = "") -> bytes:
        return f"lila:v1:{kind}:{owner_key}:{resource_key}:{suffix}".encode("ascii")

    def _encrypt(self, plaintext: bytes, aad: bytes) -> tuple[bytes, bytes]:
        nonce = os.urandom(self._NONCE_BYTES)
        return nonce, self._cipher.encrypt(nonce, plaintext, aad)

    def _decrypt(self, nonce: bytes, ciphertext: bytes, aad: bytes) -> bytes:
        try:
            return self._cipher.decrypt(bytes(nonce), bytes(ciphertext), aad)
        except (InvalidTag, ValueError, TypeError):
            raise LilaStoreIntegrityError("Stored Lila data failed authentication") from None

    def _decode_memory(
        self, owner_key: str, memory_key: str, nonce: bytes, ciphertext: bytes
    ) -> MemorySnippet:
        plaintext = self._decrypt(
            nonce, ciphertext, self._aad("memory", owner_key, memory_key)
        )
        try:
            payload = json.loads(plaintext.decode("utf-8"))
            return MemorySnippet.model_validate(payload)
        except Exception:
            raise LilaStoreIntegrityError("Stored Lila data failed validation") from None

    @staticmethod
    def _tokens(value: str, limit: int = 256) -> tuple[str, ...]:
        """Return a stable, bounded set of Unicode word tokens."""
        seen: set[str] = set()
        tokens: list[str] = []
        for match in re.finditer(r"[^\W_]+", value.casefold(), flags=re.UNICODE):
            token = match.group(0)
            if token in seen:
                continue
            seen.add(token)
            tokens.append(token)
            if len(tokens) >= limit:
                break
        return tuple(tokens)

    def _term_keys(
        self, owner_key: str, value: str, limit: int = 256
    ) -> tuple[str, ...]:
        """Hash normalized terms with a domain-separated, owner-bound HMAC."""
        return tuple(sorted(
            hmac.new(
                self._index_key,
                b"memory-term-v1\0"
                + owner_key.encode("ascii")
                + b"\0"
                + token.encode("utf-8"),
                hashlib.sha256,
            ).hexdigest()
            for token in self._tokens(value, limit)
        ))


    async def load_recent_messages(self, *, principal_id: str, conversation_id: str, limit: int) -> Sequence[ConversationMessage]:
        if not 0 <= limit <= 100:
            raise ValueError("history limit must be between 0 and 100")
        if limit == 0:
            return ()
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        conversation_key = self._resource_key("conversation", conversation_id, 128)
        return await asyncio.to_thread(self._load_recent_sync, owner_key, conversation_key, limit)

    def _load_recent_sync(self, owner_key: str, conversation_key: str, limit: int) -> tuple[ConversationMessage, ...]:
        connection = self._connect()
        try:
            rows = connection.execute(
                "SELECT sequence, role, nonce, ciphertext FROM lila_messages "
                "WHERE owner_key=? AND conversation_key=? ORDER BY sequence DESC LIMIT ?",
                (owner_key, conversation_key, limit),
            ).fetchall()
        finally:
            connection.close()
        messages = []
        for row in reversed(rows):
            sequence = int(row["sequence"])
            role = str(row["role"])
            plaintext = self._decrypt(
                row["nonce"],
                row["ciphertext"],
                self._aad("message", owner_key, conversation_key, f"{sequence}:{role}"),
            )
            try:
                content = plaintext.decode("utf-8")
                messages.append(ConversationMessage(role=role, content=content))
            except Exception:
                raise LilaStoreIntegrityError("Stored Lila data failed validation") from None
        return tuple(messages)

    async def load_conversation_page(
        self,
        *,
        principal_id: str,
        conversation_id: str,
        limit: int = 20,
        before_sequence: int | None = None,
    ) -> tuple[tuple[StoredConversationMessage, ...], int | None]:
        """Load one bounded page, oldest first, for one authenticated owner.

        The cursor is exclusive: a subsequent page returns only lower sequence
        values. Unknown conversations intentionally return an empty page.
        """
        if not 1 <= limit <= 20:
            raise ValueError("history page limit must be between 1 and 20")
        if before_sequence is not None and before_sequence < 1:
            raise ValueError("history cursor must be positive")
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        conversation_key = self._resource_key("conversation", conversation_id, 128)
        return await asyncio.to_thread(
            self._load_conversation_page_sync,
            owner_key,
            conversation_key,
            limit,
            before_sequence,
        )

    def _load_conversation_page_sync(
        self,
        owner_key: str,
        conversation_key: str,
        limit: int,
        before_sequence: int | None,
    ) -> tuple[tuple[StoredConversationMessage, ...], int | None]:
        connection = self._connect()
        try:
            if before_sequence is None:
                rows = connection.execute(
                    "SELECT m.sequence, m.role, m.nonce, m.ciphertext, m.created_at_ms, "
                    "t.provider_id, t.model_id, t.fallback_from_provider_id, "
                    "t.fallback_from_model_id, t.fallback_reason FROM lila_messages AS m "
                    "LEFT JOIN lila_turn_metadata AS t ON "
                    "t.owner_key=m.owner_key AND t.conversation_key=m.conversation_key "
                    "AND t.assistant_sequence=m.sequence "
                    "WHERE m.owner_key=? AND m.conversation_key=? "
                    "ORDER BY m.sequence DESC LIMIT ?",
                    (owner_key, conversation_key, limit + 1),
                ).fetchall()
            else:
                rows = connection.execute(
                    "SELECT m.sequence, m.role, m.nonce, m.ciphertext, m.created_at_ms, "
                    "t.provider_id, t.model_id, t.fallback_from_provider_id, "
                    "t.fallback_from_model_id, t.fallback_reason FROM lila_messages AS m "
                    "LEFT JOIN lila_turn_metadata AS t ON "
                    "t.owner_key=m.owner_key AND t.conversation_key=m.conversation_key "
                    "AND t.assistant_sequence=m.sequence "
                    "WHERE m.owner_key=? AND m.conversation_key=? AND m.sequence < ? "
                    "ORDER BY m.sequence DESC LIMIT ?",
                    (owner_key, conversation_key, before_sequence, limit + 1),
                ).fetchall()
        finally:
            connection.close()

        has_more = len(rows) > limit
        page_rows = rows[:limit]
        messages: list[StoredConversationMessage] = []
        for row in reversed(page_rows):
            sequence = int(row["sequence"])
            role = str(row["role"])
            plaintext = self._decrypt(
                row["nonce"],
                row["ciphertext"],
                self._aad("message", owner_key, conversation_key, f"{sequence}:{role}"),
            )
            try:
                messages.append(
                    StoredConversationMessage(
                        sequence=sequence,
                        role=role,
                        content=plaintext.decode("utf-8"),
                        created_at_epoch_millis=int(row["created_at_ms"]),
                        provider_id=row["provider_id"],
                        model_id=row["model_id"],
                        fallback_used=row["fallback_from_provider_id"] is not None,
                        fallback_from_provider_id=row["fallback_from_provider_id"],
                        fallback_from_model_id=row["fallback_from_model_id"],
                        fallback_reason=row["fallback_reason"],
                    )
                )
            except Exception:
                raise LilaStoreIntegrityError("Stored Lila data failed validation") from None

        next_cursor = int(page_rows[-1]["sequence"]) if has_more and page_rows else None
        return tuple(messages), next_cursor

    def _idempotency_material(
        self,
        principal_id: str,
        conversation_id: str,
        idempotency_key: str,
        user_message: str,
    ) -> tuple[str, str, str, str]:
        if not isinstance(idempotency_key, str) or not idempotency_key.strip():
            raise ValueError("idempotency_key must be a non-empty string")
        owner_key = self._owner_key(principal_id)
        conversation_key = self._resource_key("conversation", conversation_id, 128)
        request_key = self._resource_key("turn", idempotency_key, 128)
        digest = hmac.new(
            self._index_key,
            b"lila-idempotency-request-v1"
            + bytes((0,))
            + owner_key.encode("ascii")
            + bytes((0,))
            + conversation_key.encode("ascii")
            + bytes((0,))
            + user_message.encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        return owner_key, conversation_key, request_key, digest

    async def reserve_idempotent_turn(
        self,
        *,
        principal_id: str,
        conversation_id: str,
        idempotency_key: str,
        user_message: str,
    ) -> CoreTurnResponse | None:
        ConversationMessage(role="user", content=user_message)
        await self.initialize()
        owner_key, conversation_key, request_key, digest = self._idempotency_material(
            principal_id, conversation_id, idempotency_key, user_message
        )
        return await asyncio.to_thread(
            self._reserve_idempotent_turn_sync,
            owner_key,
            conversation_key,
            request_key,
            digest,
            conversation_id,
        )

    async def get_idempotent_turn_result(
        self,
        *,
        principal_id: str,
        conversation_id: str,
        idempotency_key: str,
    ) -> tuple[str, CoreTurnResponse | None]:
        """Read one owner's turn status without re-running provider generation."""
        if not isinstance(idempotency_key, str) or not idempotency_key.strip():
            raise ValueError("idempotency_key must be a non-empty string")
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        conversation_key = self._resource_key("conversation", conversation_id, 128)
        request_key = self._resource_key("turn", idempotency_key, 128)
        return await asyncio.to_thread(
            self._get_idempotent_turn_result_sync,
            owner_key,
            conversation_key,
            request_key,
            conversation_id,
        )

    def _get_idempotent_turn_result_sync(
        self,
        owner_key: str,
        conversation_key: str,
        request_key: str,
        conversation_id: str,
    ) -> tuple[str, CoreTurnResponse | None]:
        cached: tuple[bytes, bytes] | None = None
        connection = self._connect()
        try:
            row = connection.execute(
                "SELECT conversation_key, state, response_nonce, response_ciphertext "
                "FROM lila_turn_requests WHERE owner_key=? AND request_key=?",
                (owner_key, request_key),
            ).fetchone()
            if row is None or row["conversation_key"] != conversation_key:
                return "not_found", None
            if row["state"] == "pending":
                return "pending", None
            if (
                row["state"] != "completed"
                or row["response_nonce"] is None
                or row["response_ciphertext"] is None
            ):
                raise LilaStoreIntegrityError("Stored Lila idempotency result is invalid")
            cached = (bytes(row["response_nonce"]), bytes(row["response_ciphertext"]))
        finally:
            connection.close()

        plaintext = self._decrypt(
            cached[0],
            cached[1],
            self._aad("turn_response", owner_key, request_key),
        )
        try:
            response = CoreTurnResponse.model_validate_json(plaintext)
        except Exception:
            raise LilaStoreIntegrityError("Stored Lila idempotency result failed validation") from None
        if response.conversation_id != conversation_id:
            raise LilaStoreIntegrityError("Stored Lila idempotency result belongs to another conversation")
        return "completed", response

    def _reserve_idempotent_turn_sync(
        self,
        owner_key: str,
        conversation_key: str,
        request_key: str,
        request_digest: str,
        conversation_id: str,
    ) -> CoreTurnResponse | None:
        cached: tuple[bytes, bytes] | None = None
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            try:
                row = connection.execute(
                    "SELECT conversation_key, request_digest, state, response_nonce, response_ciphertext "
                    "FROM lila_turn_requests WHERE owner_key=? AND request_key=?",
                    (owner_key, request_key),
                ).fetchone()
                if row is None:
                    now = int(time.time() * 1000)
                    connection.execute(
                        "INSERT INTO lila_turn_requests(owner_key, request_key, conversation_key, "
                        "request_digest, state, response_nonce, response_ciphertext, created_at_ms, updated_at_ms) "
                        "VALUES(?, ?, ?, ?, 'pending', NULL, NULL, ?, ?)",
                        (owner_key, request_key, conversation_key, request_digest, now, now),
                    )
                    connection.execute("COMMIT")
                    return None

                if (
                    row["conversation_key"] != conversation_key
                    or not hmac.compare_digest(str(row["request_digest"]), request_digest)
                ):
                    raise IdempotencyKeyConflict(
                        "Idempotency key was already used for a different request"
                    )
                if row["state"] == "pending":
                    raise IdempotentTurnInProgress(
                        "An idempotent turn with this key is still pending"
                    )
                if row["state"] != "completed" or row["response_nonce"] is None:
                    raise LilaStoreIntegrityError("Stored Lila idempotency result is invalid")
                cached = (bytes(row["response_nonce"]), bytes(row["response_ciphertext"]))
                connection.execute("COMMIT")
            except BaseException:
                if connection.in_transaction:
                    connection.execute("ROLLBACK")
                raise
        finally:
            connection.close()

        plaintext = self._decrypt(
            cached[0],
            cached[1],
            self._aad("turn_response", owner_key, request_key),
        )
        try:
            response = CoreTurnResponse.model_validate_json(plaintext)
        except Exception:
            raise LilaStoreIntegrityError("Stored Lila idempotency result failed validation") from None
        if response.conversation_id != conversation_id:
            raise LilaStoreIntegrityError("Stored Lila idempotency result belongs to another conversation")
        return response

    async def complete_idempotent_turn(
        self,
        *,
        principal_id: str,
        conversation_id: str,
        idempotency_key: str,
        user_message: str,
        assistant_message: str,
        response: CoreTurnResponse,
    ) -> None:
        user = ConversationMessage(role="user", content=user_message)
        assistant = ConversationMessage(role="assistant", content=assistant_message)
        validated_response = CoreTurnResponse.model_validate(response)
        if validated_response.conversation_id != conversation_id:
            raise ValueError("idempotent response conversation does not match the request")
        await self.initialize()
        owner_key, conversation_key, request_key, digest = self._idempotency_material(
            principal_id, conversation_id, idempotency_key, user_message
        )
        response_payload = bytearray(validated_response.model_dump_json().encode("utf-8"))
        try:
            nonce, ciphertext = self._encrypt(
                bytes(response_payload),
                self._aad("turn_response", owner_key, request_key),
            )
        finally:
            response_payload[:] = bytes(len(response_payload))
        await asyncio.to_thread(
            self._complete_idempotent_turn_sync,
            owner_key,
            conversation_key,
            request_key,
            digest,
            user,
            assistant,
            nonce,
            ciphertext,
            validated_response.provider_id,
            validated_response.model_id,
            validated_response.fallback_from_provider_id,
            validated_response.fallback_from_model_id,
            validated_response.fallback_reason,
        )

    def _complete_idempotent_turn_sync(
        self,
        owner_key: str,
        conversation_key: str,
        request_key: str,
        request_digest: str,
        user: ConversationMessage,
        assistant: ConversationMessage,
        response_nonce: bytes,
        response_ciphertext: bytes,
        provider_id: str | None,
        model_id: str | None,
        fallback_from_provider_id: str | None,
        fallback_from_model_id: str | None,
        fallback_reason: str | None,
    ) -> None:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            try:
                reservation = connection.execute(
                    "SELECT conversation_key, request_digest, state FROM lila_turn_requests "
                    "WHERE owner_key=? AND request_key=?",
                    (owner_key, request_key),
                ).fetchone()
                if (
                    reservation is None
                    or reservation["conversation_key"] != conversation_key
                    or not hmac.compare_digest(str(reservation["request_digest"]), request_digest)
                    or reservation["state"] != "pending"
                ):
                    raise IdempotencyKeyConflict("Idempotent turn reservation is no longer available")

                current = int(
                    connection.execute(
                        "SELECT COALESCE(MAX(sequence), 0) FROM lila_messages "
                        "WHERE owner_key=? AND conversation_key=?",
                        (owner_key, conversation_key),
                    ).fetchone()[0]
                )
                now = int(time.time() * 1000)
                for offset, message in enumerate((user, assistant), start=1):
                    sequence = current + offset
                    role = message.role
                    message_nonce, message_ciphertext = self._encrypt(
                        message.content.encode("utf-8"),
                        self._aad("message", owner_key, conversation_key, f"{sequence}:{role}"),
                    )
                    connection.execute(
                        "INSERT INTO lila_messages(owner_key, conversation_key, sequence, role, nonce, ciphertext, created_at_ms) "
                        "VALUES(?, ?, ?, ?, ?, ?, ?)",
                        (owner_key, conversation_key, sequence, role, message_nonce, message_ciphertext, now),
                    )
                self._insert_turn_metadata(
                    connection,
                    owner_key,
                    conversation_key,
                    current + 2,
                    provider_id,
                    model_id,
                    fallback_from_provider_id,
                    fallback_from_model_id,
                    fallback_reason,
                )
                updated = connection.execute(
                    "UPDATE lila_turn_requests SET state='completed', response_nonce=?, "
                    "response_ciphertext=?, updated_at_ms=? WHERE owner_key=? AND request_key=? "
                    "AND request_digest=? AND state='pending'",
                    (
                        response_nonce,
                        response_ciphertext,
                        now,
                        owner_key,
                        request_key,
                        request_digest,
                    ),
                )
                if updated.rowcount != 1:
                    raise IdempotencyKeyConflict("Idempotent turn reservation is no longer available")
                connection.execute("COMMIT")
            except BaseException:
                if connection.in_transaction:
                    connection.execute("ROLLBACK")
                raise
        finally:
            connection.close()

    async def release_idempotent_turn(
        self,
        *,
        principal_id: str,
        conversation_id: str,
        idempotency_key: str,
        user_message: str,
    ) -> None:
        await self.initialize()
        owner_key, conversation_key, request_key, digest = self._idempotency_material(
            principal_id, conversation_id, idempotency_key, user_message
        )
        await asyncio.to_thread(
            self._release_idempotent_turn_sync,
            owner_key,
            request_key,
            digest,
        )

    def _release_idempotent_turn_sync(
        self,
        owner_key: str,
        request_key: str,
        request_digest: str,
    ) -> None:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            try:
                connection.execute(
                    "DELETE FROM lila_turn_requests WHERE owner_key=? AND request_key=? "
                    "AND request_digest=? AND state='pending'",
                    (owner_key, request_key, request_digest),
                )
                connection.execute("COMMIT")
            except BaseException:
                if connection.in_transaction:
                    connection.execute("ROLLBACK")
                raise
        finally:
            connection.close()

    @staticmethod
    def _insert_turn_metadata(
        connection: sqlite3.Connection,
        owner_key: str,
        conversation_key: str,
        assistant_sequence: int,
        provider_id: str | None,
        model_id: str | None,
        fallback_from_provider_id: str | None = None,
        fallback_from_model_id: str | None = None,
        fallback_reason: str | None = None,
    ) -> None:
        fallback_values = (
            fallback_from_provider_id,
            fallback_from_model_id,
            fallback_reason,
        )
        if any(value is not None for value in fallback_values) and not all(
            value is not None for value in fallback_values
        ):
            raise ValueError("fallback history metadata must be stored together")
        if fallback_from_provider_id is not None:
            if provider_id is None or model_id is None:
                raise ValueError("fallback history requires the actual provider and model")
            if fallback_from_provider_id == provider_id:
                raise ValueError("fallback history must identify a different primary provider")
            if fallback_reason not in {
                "primary_connection_unavailable",
                "primary_provider_unavailable",
            }:
                raise ValueError("fallback history reason is invalid")
        if provider_id is None and model_id is None and fallback_from_provider_id is None:
            return
        if provider_id is not None and (
            not isinstance(provider_id, str) or not provider_id.strip() or len(provider_id) > 64
        ):
            raise ValueError("provider_id must be a non-empty identifier within 64 characters")
        if model_id is not None and (
            not isinstance(model_id, str) or not model_id.strip() or len(model_id) > 256
        ):
            raise ValueError("model_id must be a non-empty identifier within 256 characters")
        if fallback_from_provider_id is not None and (
            not isinstance(fallback_from_provider_id, str)
            or not fallback_from_provider_id.strip()
            or len(fallback_from_provider_id) > 64
        ):
            raise ValueError("fallback_from_provider_id is invalid")
        if fallback_from_model_id is not None and (
            not isinstance(fallback_from_model_id, str)
            or not fallback_from_model_id.strip()
            or len(fallback_from_model_id) > 256
        ):
            raise ValueError("fallback_from_model_id is invalid")
        connection.execute(
            "INSERT INTO lila_turn_metadata(owner_key, conversation_key, assistant_sequence, "
            "provider_id, model_id, fallback_from_provider_id, fallback_from_model_id, fallback_reason) "
            "VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
            (
                owner_key,
                conversation_key,
                assistant_sequence,
                provider_id,
                model_id,
                fallback_from_provider_id,
                fallback_from_model_id,
                fallback_reason,
            ),
        )

    async def append_turn(
        self,
        *,
        principal_id: str,
        conversation_id: str,
        user_message: str,
        assistant_message: str,
        provider_id: str | None = None,
        model_id: str | None = None,
        fallback_from_provider_id: str | None = None,
        fallback_from_model_id: str | None = None,
        fallback_reason: str | None = None,
    ) -> None:
        user = ConversationMessage(role="user", content=user_message)
        assistant = ConversationMessage(role="assistant", content=assistant_message)
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        conversation_key = self._resource_key("conversation", conversation_id, 128)
        await asyncio.to_thread(
            self._append_turn_sync,
            owner_key,
            conversation_key,
            user,
            assistant,
            provider_id,
            model_id,
            fallback_from_provider_id,
            fallback_from_model_id,
            fallback_reason,
        )

    def _append_turn_sync(
        self,
        owner_key: str,
        conversation_key: str,
        user: ConversationMessage,
        assistant: ConversationMessage,
        provider_id: str | None,
        model_id: str | None,
        fallback_from_provider_id: str | None,
        fallback_from_model_id: str | None,
        fallback_reason: str | None,
    ) -> None:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            try:
                current = int(
                    connection.execute(
                        "SELECT COALESCE(MAX(sequence), 0) FROM lila_messages "
                        "WHERE owner_key=? AND conversation_key=?",
                        (owner_key, conversation_key),
                    ).fetchone()[0]
                )
                now = int(time.time() * 1000)
                for offset, message in enumerate((user, assistant), start=1):
                    sequence = current + offset
                    role = message.role
                    nonce, ciphertext = self._encrypt(
                        message.content.encode("utf-8"),
                        self._aad("message", owner_key, conversation_key, f"{sequence}:{role}"),
                    )
                    connection.execute(
                        "INSERT INTO lila_messages(owner_key, conversation_key, sequence, role, nonce, ciphertext, created_at_ms) "
                        "VALUES(?, ?, ?, ?, ?, ?, ?)",
                        (owner_key, conversation_key, sequence, role, nonce, ciphertext, now),
                    )
                self._insert_turn_metadata(
                    connection,
                    owner_key,
                    conversation_key,
                    current + 2,
                    provider_id,
                    model_id,
                    fallback_from_provider_id,
                    fallback_from_model_id,
                    fallback_reason,
                )
                connection.execute("COMMIT")
            except BaseException:
                connection.execute("ROLLBACK")
                raise
        finally:
            connection.close()

    async def save_memory(self, *, principal_id: str, memory: MemorySnippet) -> None:
        validated = MemorySnippet.model_validate(memory)
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        memory_key = self._resource_key("memory", validated.memory_id, 128)
        payload = json.dumps(
            {"memory_id": validated.memory_id, "content": validated.content},
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        nonce, ciphertext = self._encrypt(
            payload, self._aad("memory", owner_key, memory_key)
        )
        term_keys = self._term_keys(owner_key, validated.content)
        await asyncio.to_thread(
            self._save_memory_sync, owner_key, memory_key, nonce, ciphertext, term_keys
        )

    def _save_memory_sync(
        self,
        owner_key: str,
        memory_key: str,
        nonce: bytes,
        ciphertext: bytes,
        term_keys: tuple[str, ...],
    ) -> None:
        now = int(time.time() * 1000)
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            try:
                connection.execute(
                    """INSERT INTO lila_memories(owner_key, memory_key, nonce, ciphertext, created_at_ms, updated_at_ms)
                    VALUES(?, ?, ?, ?, ?, ?)
                    ON CONFLICT(owner_key, memory_key) DO UPDATE SET
                      nonce=excluded.nonce, ciphertext=excluded.ciphertext, updated_at_ms=excluded.updated_at_ms""",
                    (owner_key, memory_key, nonce, ciphertext, now, now),
                )
                connection.execute(
                    "DELETE FROM lila_memory_terms WHERE owner_key=? AND memory_key=?",
                    (owner_key, memory_key),
                )
                indexed_terms = term_keys or ("",)
                connection.executemany(
                    "INSERT INTO lila_memory_terms(owner_key, memory_key, term_key) VALUES(?, ?, ?)",
                    ((owner_key, memory_key, term_key) for term_key in indexed_terms),
                )
                connection.execute("COMMIT")
            except BaseException:
                connection.execute("ROLLBACK")
                raise
        finally:
            connection.close()

    async def delete_memory(self, *, principal_id: str, memory_id: str) -> bool:
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        memory_key = self._resource_key("memory", memory_id, 128)
        return await asyncio.to_thread(self._delete_memory_sync, owner_key, memory_key)

    def _delete_memory_sync(self, owner_key: str, memory_key: str) -> bool:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            try:
                cursor = connection.execute(
                    "DELETE FROM lila_memories WHERE owner_key=? AND memory_key=?",
                    (owner_key, memory_key),
                )
                connection.execute("COMMIT")
                return cursor.rowcount > 0
            except BaseException:
                connection.execute("ROLLBACK")
                raise
        finally:
            connection.close()

    async def search_relevant_memories(
        self, *, principal_id: str, query: str, limit: int
    ) -> Sequence[MemorySnippet]:
        if not 0 <= limit <= 20:
            raise ValueError("memory limit must be between 0 and 20")
        if limit == 0:
            return ()
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        return await asyncio.to_thread(self._search_memories_sync, owner_key, query, limit)

    def _search_memories_sync(
        self, owner_key: str, query: str, limit: int
    ) -> tuple[MemorySnippet, ...]:
        query_term_keys = self._term_keys(
            owner_key, query, self._MAX_QUERY_TERMS
        )
        query_term_set = set(query_term_keys)
        connection = self._connect()
        try:
            if not query_term_keys:
                # Preserve empty-query behavior without scanning/decrypting all
                # memories: only the newest bounded result window is needed.
                rows = connection.execute(
                    "SELECT memory_key, nonce, ciphertext, updated_at_ms "
                    "FROM lila_memories WHERE owner_key=? "
                    "ORDER BY updated_at_ms DESC, memory_key ASC LIMIT ?",
                    (owner_key, limit),
                ).fetchall()
            else:
                placeholders = ",".join("?" for _ in query_term_keys)
                rows = connection.execute(
                    f"""WITH matches AS (
                        SELECT owner_key, memory_key, COUNT(*) AS overlap_count
                        FROM lila_memory_terms
                        WHERE owner_key=? AND term_key IN ({placeholders})
                        GROUP BY owner_key, memory_key
                    ),
                    candidates AS (
                        SELECT
                            m.memory_key,
                            m.nonce,
                            m.ciphertext,
                            m.updated_at_ms,
                            matches.overlap_count,
                            (
                                SELECT COUNT(*)
                                FROM lila_memory_terms AS all_terms
                                WHERE all_terms.owner_key=m.owner_key
                                  AND all_terms.memory_key=m.memory_key
                                  AND all_terms.term_key <> ''
                            ) AS term_count
                        FROM matches
                        JOIN lila_memories AS m
                          ON m.owner_key=matches.owner_key
                         AND m.memory_key=matches.memory_key
                    )
                    SELECT memory_key, nonce, ciphertext, updated_at_ms,
                           overlap_count, term_count
                    FROM candidates
                    ORDER BY
                        (1.0 * overlap_count) /
                            (? + term_count - overlap_count) DESC,
                        updated_at_ms DESC,
                        memory_key ASC
                    LIMIT ?""",
                    (
                        owner_key,
                        *query_term_keys,
                        len(query_term_keys),
                        self._MAX_MEMORY_CANDIDATES,
                    ),
                ).fetchall()
        finally:
            connection.close()

        ranked: list[tuple[float, int, MemorySnippet]] = []
        for row in rows:
            memory_key = str(row["memory_key"])
            memory = self._decode_memory(
                owner_key, memory_key, row["nonce"], row["ciphertext"]
            )
            if query_term_keys:
                # Recompute against authenticated plaintext so a stale or
                # modified auxiliary index cannot create false-positive matches.
                memory_term_set = set(
                    self._term_keys(owner_key, memory.content, self._MAX_MEMORY_TERMS)
                )
                overlap = len(query_term_set & memory_term_set)
                if overlap == 0:
                    continue
                score = overlap / len(query_term_set | memory_term_set)
            else:
                score = 0.0
            ranked.append((score, int(row["updated_at_ms"]), memory))

        ranked.sort(key=lambda item: (-item[0], -item[1], item[2].memory_id))
        return tuple(item[2] for item in ranked[:limit])

    _MAX_PROVIDERS_PER_OWNER = 20

    def _decode_provider(
        self,
        owner_key: str,
        provider_key: str,
        nonce: bytes,
        ciphertext: bytes,
    ) -> dict[str, object]:
        plaintext = bytearray(
            self._decrypt(
                nonce,
                ciphertext,
                self._aad("provider", owner_key, provider_key),
            )
        )
        try:
            payload = json.loads(plaintext.decode("utf-8"))
            if not isinstance(payload, dict):
                raise TypeError("provider payload must be an object")
            provider_id = payload.get("provider_id")
            if not isinstance(provider_id, str):
                raise TypeError("provider identifier is invalid")
            if self._resource_key("provider", provider_id, 64) != provider_key:
                raise TypeError("provider identifier does not match its authenticated index")
            required = {
                "provider_type", "base_url", "model_id", "api_key",
                "timeout_seconds", "capabilities", "enabled",
            }
            if not required.issubset(payload):
                raise TypeError("provider configuration is incomplete")
            if (
                not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", provider_id)
                or payload.get("provider_type") not in {"cloud", "local"}
                or not isinstance(payload.get("base_url"), str)
                or not 1 <= len(payload["base_url"]) <= 2048
                or not isinstance(payload.get("model_id"), str)
                or not 1 <= len(payload["model_id"]) <= 256
                or (
                    payload.get("api_key") is not None
                    and not isinstance(payload.get("api_key"), str)
                )
                or not isinstance(payload.get("timeout_seconds"), (int, float))
                or not 1 <= float(payload["timeout_seconds"]) <= 120
                or payload.get("capabilities") != ["chat"]
                or not isinstance(payload.get("enabled"), bool)
            ):
                raise TypeError("provider configuration fields are invalid")
            return payload
        except LilaStoreIntegrityError:
            raise
        except Exception:
            raise LilaStoreIntegrityError(
                "Stored Lila provider configuration failed validation"
            ) from None
        finally:
            plaintext[:] = bytes(len(plaintext))

    async def save_provider(
        self,
        *,
        principal_id: str,
        provider: dict[str, object],
        create_only: bool = False,
    ) -> bool:
        """Encrypt and save one provider. Return True when a new record was created."""
        if not isinstance(provider, dict):
            raise ValueError("provider configuration must be an object")
        await self.initialize()
        provider_id = provider.get("provider_id")
        provider_key = self._resource_key("provider", provider_id, 64)
        owner_key = self._owner_key(principal_id)
        payload = bytearray(
            json.dumps(provider, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        )
        try:
            nonce, ciphertext = self._encrypt(
                bytes(payload), self._aad("provider", owner_key, provider_key)
            )
        finally:
            payload[:] = bytes(len(payload))
        return await asyncio.to_thread(
            self._save_provider_sync,
            owner_key,
            provider_key,
            nonce,
            ciphertext,
            create_only,
        )

    def _save_provider_sync(
        self,
        owner_key: str,
        provider_key: str,
        nonce: bytes,
        ciphertext: bytes,
        create_only: bool,
    ) -> bool:
        now = int(time.time() * 1000)
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            try:
                existing = connection.execute(
                    "SELECT 1 FROM lila_providers WHERE owner_key=? AND provider_key=?",
                    (owner_key, provider_key),
                ).fetchone()
                created = existing is None
                if create_only and not created:
                    raise ProviderAlreadyExists("Provider already exists")
                if created:
                    count = int(
                        connection.execute(
                            "SELECT COUNT(*) FROM lila_providers WHERE owner_key=?",
                            (owner_key,),
                        ).fetchone()[0]
                    )
                    if count >= self._MAX_PROVIDERS_PER_OWNER:
                        raise ProviderLimitExceeded("Provider catalog limit reached")
                    connection.execute(
                        "INSERT INTO lila_providers(owner_key, provider_key, nonce, ciphertext, created_at_ms, updated_at_ms) "
                        "VALUES(?, ?, ?, ?, ?, ?)",
                        (owner_key, provider_key, nonce, ciphertext, now, now),
                    )
                else:
                    connection.execute(
                        "UPDATE lila_providers SET nonce=?, ciphertext=?, updated_at_ms=? "
                        "WHERE owner_key=? AND provider_key=?",
                        (nonce, ciphertext, now, owner_key, provider_key),
                    )
                connection.execute("COMMIT")
                return created
            except BaseException:
                if connection.in_transaction:
                    connection.execute("ROLLBACK")
                raise
        finally:
            connection.close()

    async def get_provider(
        self, *, principal_id: str, provider_id: str
    ) -> dict[str, object] | None:
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        provider_key = self._resource_key("provider", provider_id, 64)
        return await asyncio.to_thread(
            self._get_provider_sync, owner_key, provider_key
        )

    def _get_provider_sync(
        self, owner_key: str, provider_key: str
    ) -> dict[str, object] | None:
        connection = self._connect()
        try:
            row = connection.execute(
                "SELECT nonce, ciphertext FROM lila_providers WHERE owner_key=? AND provider_key=?",
                (owner_key, provider_key),
            ).fetchone()
        finally:
            connection.close()
        if row is None:
            return None
        return self._decode_provider(
            owner_key, provider_key, row["nonce"], row["ciphertext"]
        )

    async def list_providers(
        self, *, principal_id: str
    ) -> tuple[dict[str, object], ...]:
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        return await asyncio.to_thread(self._list_providers_sync, owner_key)

    def _list_providers_sync(
        self, owner_key: str
    ) -> tuple[dict[str, object], ...]:
        connection = self._connect()
        try:
            rows = connection.execute(
                "SELECT provider_key, nonce, ciphertext FROM lila_providers "
                "WHERE owner_key=? ORDER BY updated_at_ms DESC, provider_key ASC",
                (owner_key,),
            ).fetchall()
        finally:
            connection.close()
        providers = [
            self._decode_provider(
                owner_key, str(row["provider_key"]), row["nonce"], row["ciphertext"]
            )
            for row in rows
        ]
        providers.sort(key=lambda item: str(item.get("provider_id", "")))
        return tuple(providers)

    async def delete_provider(
        self, *, principal_id: str, provider_id: str
    ) -> bool:
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        provider_key = self._resource_key("provider", provider_id, 64)
        return await asyncio.to_thread(
            self._delete_provider_sync, owner_key, provider_key
        )

    def _delete_provider_sync(self, owner_key: str, provider_key: str) -> bool:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            try:
                cursor = connection.execute(
                    "DELETE FROM lila_providers WHERE owner_key=? AND provider_key=?",
                    (owner_key, provider_key),
                )
                connection.execute("COMMIT")
                return cursor.rowcount == 1
            except BaseException:
                if connection.in_transaction:
                    connection.execute("ROLLBACK")
                raise
        finally:
            connection.close()

    @staticmethod
    def _validate_model_selection(provider_id: str, model_id: str) -> None:
        if (
            not isinstance(provider_id, str)
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", provider_id)
        ):
            raise ValueError("provider_id is invalid")
        if (
            not isinstance(model_id, str)
            or not 1 <= len(model_id) <= 256
            or not model_id.strip()
            or any(ord(character) < 32 or ord(character) == 127 for character in model_id)
        ):
            raise ValueError("model_id is invalid")

    def _decode_conversation_model(
        self,
        owner_key: str,
        conversation_key: str,
        provider_key: str,
        nonce: bytes,
        ciphertext: bytes,
    ) -> dict[str, str | None]:
        plaintext = bytearray(
            self._decrypt(
                nonce,
                ciphertext,
                self._aad("conversation_model", owner_key, conversation_key, provider_key),
            )
        )
        try:
            payload = json.loads(plaintext.decode("utf-8"))
            if not isinstance(payload, dict):
                raise TypeError("conversation model payload must be an object")
            provider_id = payload.get("provider_id")
            model_id = payload.get("model_id")
            self._validate_model_selection(provider_id, model_id)
            if self._resource_key("provider", provider_id, 64) != provider_key:
                raise TypeError("provider identifier does not match its authenticated index")
            result: dict[str, str | None] = {
                "provider_id": provider_id,
                "model_id": model_id,
            }
            fallback_provider_id = payload.get("fallback_provider_id")
            fallback_model_id = payload.get("fallback_model_id")
            if (fallback_provider_id is None) != (fallback_model_id is None):
                raise TypeError("fallback provider and model must be stored together")
            if fallback_provider_id is not None:
                self._validate_model_selection(fallback_provider_id, fallback_model_id)
                if fallback_provider_id == provider_id:
                    raise TypeError("fallback provider must differ from primary provider")
                result["fallback_provider_id"] = fallback_provider_id
                result["fallback_model_id"] = fallback_model_id
            return result
        except LilaStoreIntegrityError:
            raise
        except Exception:
            raise LilaStoreIntegrityError(
                "Stored Lila conversation model selection failed validation"
            ) from None
        finally:
            plaintext[:] = bytes(len(plaintext))

    async def set_conversation_model(
        self,
        *,
        principal_id: str,
        conversation_id: str,
        provider_id: str,
        model_id: str,
        fallback_provider_id: str | None = None,
        fallback_model_id: str | None = None,
    ) -> None:
        """Persist an encrypted owner-scoped primary and optional fallback selection."""
        self._validate_model_selection(provider_id, model_id)
        if (fallback_provider_id is None) != (fallback_model_id is None):
            raise ValueError("fallback provider and model must be configured together")
        if fallback_provider_id is not None:
            self._validate_model_selection(fallback_provider_id, fallback_model_id)
            if fallback_provider_id == provider_id:
                raise ValueError("fallback must use a different provider")
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        conversation_key = self._resource_key("conversation", conversation_id, 128)
        provider_key = self._resource_key("provider", provider_id, 64)
        fallback_provider_key = (
            self._resource_key("provider", fallback_provider_id, 64)
            if fallback_provider_id is not None else None
        )
        selection_payload = {"provider_id": provider_id, "model_id": model_id}
        if fallback_provider_id is not None:
            selection_payload.update({
                "fallback_provider_id": fallback_provider_id,
                "fallback_model_id": fallback_model_id,
            })
        payload = bytearray(
            json.dumps(
                selection_payload,
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode("utf-8")
        )
        try:
            nonce, ciphertext = self._encrypt(
                bytes(payload),
                self._aad("conversation_model", owner_key, conversation_key, provider_key),
            )
        finally:
            payload[:] = bytes(len(payload))
        await asyncio.to_thread(
            self._set_conversation_model_sync,
            owner_key,
            conversation_key,
            provider_key,
            fallback_provider_key,
            nonce,
            ciphertext,
        )

    def _set_conversation_model_sync(
        self,
        owner_key: str,
        conversation_key: str,
        provider_key: str,
        fallback_provider_key: str | None,
        nonce: bytes,
        ciphertext: bytes,
    ) -> None:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            try:
                provider_row = connection.execute(
                    "SELECT nonce, ciphertext FROM lila_providers WHERE owner_key=? AND provider_key=?",
                    (owner_key, provider_key),
                ).fetchone()
                if provider_row is None:
                    raise ProviderNotFound("Provider not found for owner")
                provider = self._decode_provider(
                    owner_key, provider_key, provider_row["nonce"], provider_row["ciphertext"]
                )
                if not provider["enabled"]:
                    raise ProviderDisabled("Provider is disabled")
                if fallback_provider_key is not None:
                    if fallback_provider_key == provider_key:
                        raise ValueError("fallback must use a different provider")
                    fallback_row = connection.execute(
                        "SELECT nonce, ciphertext FROM lila_providers WHERE owner_key=? AND provider_key=?",
                        (owner_key, fallback_provider_key),
                    ).fetchone()
                    if fallback_row is None:
                        raise ProviderNotFound("Fallback provider not found for owner")
                    fallback_provider = self._decode_provider(
                        owner_key,
                        fallback_provider_key,
                        fallback_row["nonce"],
                        fallback_row["ciphertext"],
                    )
                    if not fallback_provider["enabled"]:
                        raise ProviderDisabled("Fallback provider is disabled")
                    if "chat" not in fallback_provider.get("capabilities", []):
                        raise ValueError("Fallback provider does not support chat")
                now = int(time.time() * 1000)
                connection.execute(
                    """INSERT INTO lila_conversation_models(
                        owner_key, conversation_key, provider_key, nonce, ciphertext,
                        created_at_ms, updated_at_ms
                    ) VALUES(?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(owner_key, conversation_key) DO UPDATE SET
                        provider_key=excluded.provider_key,
                        nonce=excluded.nonce,
                        ciphertext=excluded.ciphertext,
                        updated_at_ms=excluded.updated_at_ms""",
                    (owner_key, conversation_key, provider_key, nonce, ciphertext, now, now),
                )
                connection.execute("COMMIT")
            except BaseException:
                if connection.in_transaction:
                    connection.execute("ROLLBACK")
                raise
        finally:
            connection.close()

    async def get_conversation_model(
        self,
        *,
        principal_id: str,
        conversation_id: str,
    ) -> dict[str, str | None] | None:
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        conversation_key = self._resource_key("conversation", conversation_id, 128)
        return await asyncio.to_thread(
            self._get_conversation_model_sync, owner_key, conversation_key
        )

    def _get_conversation_model_sync(
        self, owner_key: str, conversation_key: str
    ) -> dict[str, str] | None:
        connection = self._connect()
        try:
            row = connection.execute(
                """SELECT provider_key, nonce, ciphertext
                FROM lila_conversation_models
                WHERE owner_key=? AND conversation_key=?""",
                (owner_key, conversation_key),
            ).fetchone()
        finally:
            connection.close()
        if row is None:
            return None
        return self._decode_conversation_model(
            owner_key,
            conversation_key,
            str(row["provider_key"]),
            row["nonce"],
            row["ciphertext"],
        )

