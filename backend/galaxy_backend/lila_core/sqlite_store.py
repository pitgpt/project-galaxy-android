"""Encrypted, owner-scoped SQLite persistence for Lila conversations and memories.

The database contains only HMAC-derived owner/resource indexes and AES-GCM
ciphertexts. The 32-byte master key is supplied by the application composition
root and is never persisted by this adapter.
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

from .memory import ConversationMessage, MemorySnippet


class LilaStoreIntegrityError(RuntimeError):
    """Stored Lila data failed authenticated decryption or validation."""


class SqliteLilaStore:
    """One-file encrypted store implementing history and memory reader/writers.

    SQLite WAL plus FULL synchronous commits provide local durability. A
    production deployment must place the absolute database path on a persistent
    volume and supply the master key from a secret manager/environment secret.
    This adapter does not make an ephemeral container filesystem durable.
    """

    _SCHEMA_VERSION = 1
    _NONCE_BYTES = 12

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
                    connection.execute(f"PRAGMA user_version={self._SCHEMA_VERSION}")
                elif version != self._SCHEMA_VERSION:
                    raise RuntimeError("Unsupported Lila database schema version")
                else:
                    row = connection.execute(
                        "SELECT key_check FROM lila_meta WHERE singleton=1"
                    ).fetchone()
                    if row is None or not hmac.compare_digest(bytes(row[0]), self._key_check()):
                        raise LilaStoreIntegrityError("Lila storage key does not match the database")
                connection.execute("COMMIT")
            except BaseException:
                connection.execute("ROLLBACK")
                raise
        finally:
            connection.close()

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

    async def append_turn(
        self,
        *,
        principal_id: str,
        conversation_id: str,
        user_message: str,
        assistant_message: str,
    ) -> None:
        user = ConversationMessage(role="user", content=user_message)
        assistant = ConversationMessage(role="assistant", content=assistant_message)
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        conversation_key = self._resource_key("conversation", conversation_id, 128)
        await asyncio.to_thread(
            self._append_turn_sync, owner_key, conversation_key, user, assistant
        )

    def _append_turn_sync(
        self,
        owner_key: str,
        conversation_key: str,
        user: ConversationMessage,
        assistant: ConversationMessage,
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
        await asyncio.to_thread(
            self._save_memory_sync, owner_key, memory_key, nonce, ciphertext
        )

    def _save_memory_sync(
        self, owner_key: str, memory_key: str, nonce: bytes, ciphertext: bytes
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

    @staticmethod
    def _tokens(value: str) -> set[str]:
        return set(re.findall(r"[^\W_]+", value.casefold(), flags=re.UNICODE))

    def _search_memories_sync(
        self, owner_key: str, query: str, limit: int
    ) -> tuple[MemorySnippet, ...]:
        connection = self._connect()
        try:
            rows = connection.execute(
                "SELECT memory_key, nonce, ciphertext, updated_at_ms "
                "FROM lila_memories WHERE owner_key=?",
                (owner_key,),
            ).fetchall()
        finally:
            connection.close()
        query_tokens = self._tokens(query)
        ranked: list[tuple[float, int, MemorySnippet]] = []
        for row in rows:
            memory_key = str(row["memory_key"])
            plaintext = self._decrypt(
                row["nonce"],
                row["ciphertext"],
                self._aad("memory", owner_key, memory_key),
            )
            try:
                payload = json.loads(plaintext.decode("utf-8"))
                memory = MemorySnippet.model_validate(payload)
            except Exception:
                raise LilaStoreIntegrityError("Stored Lila data failed validation") from None
            memory_tokens = self._tokens(memory.content)
            if query_tokens:
                overlap = len(query_tokens & memory_tokens)
                if overlap == 0:
                    continue
                score = overlap / len(query_tokens | memory_tokens)
            else:
                score = 0.0
            ranked.append((score, int(row["updated_at_ms"]), memory))
        ranked.sort(key=lambda item: (-item[0], -item[1], item[2].memory_id))
        return tuple(item[2] for item in ranked[:limit])
