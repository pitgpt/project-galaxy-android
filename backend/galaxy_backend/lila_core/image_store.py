"""Owner-scoped encrypted persistence for Lila image jobs and ComfyUI settings."""
from __future__ import annotations

import asyncio
import base64
import binascii
import hashlib
import hmac
import json
import os
import sqlite3
import stat
import time
from pathlib import Path
from threading import Lock
from typing import Any
from uuid import UUID, uuid4, uuid5, NAMESPACE_URL

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


class ImageStoreIntegrityError(RuntimeError):
    """Encrypted image data failed authenticated decryption or validation."""


class ImageIdempotencyConflict(RuntimeError):
    """An image idempotency key was reused for a different request."""


class ImageJobNotFound(RuntimeError):
    """The requested image job does not belong to the authenticated owner."""


class SqliteLilaImageStore:
    """Encrypted image-provider settings, durable jobs, and encrypted result bytes.

    This store shares the existing Lila SQLite file but uses independently derived
    keys and a separate schema marker. Identifiers are HMAC indexes; prompts,
    endpoint settings, bearer tokens, workflow JSON, and image bytes are AES-GCM
    ciphertext. The store never creates a second database.
    """

    _SCHEMA_VERSION = 1
    _NONCE_BYTES = 12
    _MAX_PROVIDER_CONFIG_BYTES = 2 * 1024 * 1024 + 16 * 1024
    _MAX_IMAGE_BYTES = 25 * 1024 * 1024
    _STATES = {
        "prepared", "submitting", "submission_unknown", "queued", "running",
        "provider_completed", "result_pending", "completed", "failed", "cancelled",
    }
    _TRANSITIONS = {
        "prepared": {"submitting", "failed", "cancelled"},
        "submitting": {"queued", "submission_unknown", "running", "provider_completed", "failed"},
        "submission_unknown": {"queued", "running", "provider_completed", "failed"},
        "queued": {"running", "provider_completed", "failed", "cancelled", "submission_unknown"},
        "running": {"provider_completed", "failed", "submission_unknown"},
        "provider_completed": {"completed", "result_pending", "failed"},
        "result_pending": {"completed", "failed"},
        "completed": set(),
        "failed": set(),
        "cancelled": set(),
    }

    def __init__(self, database_path: str | Path, encryption_key: bytes) -> None:
        path = Path(database_path)
        if not path.is_absolute() or path.name in {"", ".", ".."}:
            raise ValueError("Lila image database path must be an absolute file path")
        if not isinstance(encryption_key, (bytes, bytearray)) or len(encryption_key) != 32:
            raise ValueError("Lila image storage key must contain exactly 32 bytes")
        master = bytes(encryption_key)
        self._index_key = hmac.new(master, b"lila-image-index-v1", hashlib.sha256).digest()
        encryption_key = hmac.new(master, b"lila-image-encryption-v1", hashlib.sha256).digest()
        self._cipher = AESGCM(encryption_key)
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
        if stat.S_IMODE(self._path.parent.stat().st_mode) & 0o077:
            raise RuntimeError("Lila database directory must be private to the service account")
        if self._path.is_symlink():
            raise RuntimeError("Lila database path must not be a symbolic link")
        descriptor = os.open(
            self._path,
            os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0),
            0o600,
        )
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
                raise RuntimeError("SQLite WAL mode is required for Lila image persistence")
            connection.execute("BEGIN IMMEDIATE")
            try:
                connection.execute(
                    """CREATE TABLE IF NOT EXISTS lila_image_meta (
                        singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                        schema_version INTEGER NOT NULL,
                        key_check BLOB NOT NULL
                    )"""
                )
                key_check = hmac.new(
                    self._index_key, b"lila-image-store-key-check-v1", hashlib.sha256
                ).digest()
                row = connection.execute(
                    "SELECT schema_version, key_check FROM lila_image_meta WHERE singleton=1"
                ).fetchone()
                if row is None:
                    connection.execute(
                        "INSERT INTO lila_image_meta(singleton, schema_version, key_check) VALUES(1, ?, ?)",
                        (self._SCHEMA_VERSION, key_check),
                    )
                else:
                    if not hmac.compare_digest(bytes(row["key_check"]), key_check):
                        raise ImageStoreIntegrityError("Lila image storage key does not match the database")
                    if int(row["schema_version"]) != self._SCHEMA_VERSION:
                        raise RuntimeError("Unsupported Lila image storage schema version")

                connection.execute(
                    """CREATE TABLE IF NOT EXISTS lila_image_providers (
                        owner_key TEXT NOT NULL,
                        provider_key TEXT NOT NULL,
                        nonce BLOB NOT NULL CHECK(length(nonce)=12),
                        ciphertext BLOB NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        PRIMARY KEY(owner_key, provider_key)
                    ) WITHOUT ROWID"""
                )
                connection.execute(
                    """CREATE TABLE IF NOT EXISTS lila_image_jobs (
                        owner_key TEXT NOT NULL,
                        job_key TEXT NOT NULL,
                        conversation_key TEXT NOT NULL,
                        request_key TEXT NOT NULL,
                        request_digest TEXT NOT NULL,
                        state TEXT NOT NULL CHECK(state IN (
                            'prepared','submitting','submission_unknown','queued','running',
                            'provider_completed','result_pending','completed','failed','cancelled'
                        )),
                        nonce BLOB NOT NULL CHECK(length(nonce)=12),
                        ciphertext BLOB NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        PRIMARY KEY(owner_key, job_key),
                        UNIQUE(owner_key, request_key)
                    ) WITHOUT ROWID"""
                )
                connection.execute(
                    "CREATE INDEX IF NOT EXISTS idx_lila_image_jobs_owner_conversation "
                    "ON lila_image_jobs(owner_key, conversation_key, created_at_ms DESC)"
                )
                connection.execute(
                    """CREATE TABLE IF NOT EXISTS lila_image_assets (
                        owner_key TEXT NOT NULL,
                        job_key TEXT NOT NULL,
                        mime_type TEXT NOT NULL CHECK(mime_type='image/png'),
                        width INTEGER NOT NULL CHECK(width BETWEEN 1 AND 8192),
                        height INTEGER NOT NULL CHECK(height BETWEEN 1 AND 8192),
                        byte_size INTEGER NOT NULL CHECK(byte_size BETWEEN 1 AND 26214400),
                        sha256 TEXT NOT NULL CHECK(length(sha256)=64),
                        nonce BLOB NOT NULL CHECK(length(nonce)=12),
                        ciphertext BLOB NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        PRIMARY KEY(owner_key, job_key),
                        FOREIGN KEY(owner_key, job_key)
                            REFERENCES lila_image_jobs(owner_key, job_key) ON DELETE CASCADE
                    ) WITHOUT ROWID"""
                )
                connection.execute("COMMIT")
            except BaseException:
                if connection.in_transaction:
                    connection.execute("ROLLBACK")
                raise
        finally:
            connection.close()

    def _owner_key(self, principal_id: str) -> str:
        if not isinstance(principal_id, str) or not principal_id.strip() or len(principal_id) > 256:
            raise ValueError("principal_id must be a non-empty authenticated subject")
        return hmac.new(
            self._index_key, b"owner\0" + principal_id.encode("utf-8"), hashlib.sha256
        ).hexdigest()

    def _resource_key(self, kind: str, value: str, maximum: int) -> str:
        if not isinstance(value, str) or not value.strip() or len(value) > maximum:
            raise ValueError(f"{kind} must be a non-empty string within its supported length")
        return hmac.new(
            self._index_key, kind.encode("ascii") + b"\0" + value.encode("utf-8"), hashlib.sha256
        ).hexdigest()

    def _encode_history_cursor(self, owner_key: str, job: dict[str, Any]) -> str:
        payload = json.dumps(
            {
                "v": 1,
                "created_at_ms": int(job["created_at_epoch_millis"]),
                "job_id": str(job["job_id"]),
            },
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        signature = hmac.new(
            self._index_key,
            b"lila-image-history-cursor-v1\0" + owner_key.encode("ascii") + b"\0" + payload,
            hashlib.sha256,
        ).digest()[:16]
        return base64.urlsafe_b64encode(payload + signature).rstrip(b"=").decode("ascii")

    def _decode_history_cursor(
        self, owner_key: str, cursor: str | None
    ) -> tuple[int, str] | None:
        if cursor is None:
            return None
        if not isinstance(cursor, str) or not cursor or len(cursor) > 256:
            raise ValueError("Image history cursor is invalid")
        try:
            raw = base64.b64decode(
                cursor + "=" * (-len(cursor) % 4),
                altchars=b"-_",
                validate=True,
            )
            if len(raw) <= 16:
                raise ValueError("cursor payload is too short")
            payload, signature = raw[:-16], raw[-16:]
            expected = hmac.new(
                self._index_key,
                b"lila-image-history-cursor-v1\0" + owner_key.encode("ascii") + b"\0" + payload,
                hashlib.sha256,
            ).digest()[:16]
            if not hmac.compare_digest(signature, expected):
                raise ValueError("cursor signature is invalid")
            value = json.loads(payload.decode("utf-8"))
            if not isinstance(value, dict) or set(value) != {"v", "created_at_ms", "job_id"}:
                raise ValueError("cursor shape is invalid")
            timestamp = value["created_at_ms"]
            job_id = value["job_id"]
            if value["v"] != 1 or type(timestamp) is not int or not 0 <= timestamp <= 2**63 - 1:
                raise ValueError("cursor position is invalid")
            if not isinstance(job_id, str) or str(UUID(job_id)) != job_id:
                raise ValueError("cursor job ID is invalid")
            return timestamp, self._resource_key("image_job", job_id, 64)
        except (binascii.Error, UnicodeDecodeError, json.JSONDecodeError, TypeError, KeyError, ValueError):
            raise ValueError("Image history cursor is invalid") from None

    @staticmethod
    def _aad(kind: str, owner_key: str, resource_key: str, suffix: str = "") -> bytes:
        return f"lila:image:v1:{kind}:{owner_key}:{resource_key}:{suffix}".encode("ascii")

    def _encrypt(self, plaintext: bytes, aad: bytes) -> tuple[bytes, bytes]:
        nonce = os.urandom(self._NONCE_BYTES)
        return nonce, self._cipher.encrypt(nonce, plaintext, aad)

    def _decrypt(self, nonce: bytes, ciphertext: bytes, aad: bytes) -> bytes:
        try:
            return self._cipher.decrypt(bytes(nonce), bytes(ciphertext), aad)
        except (InvalidTag, ValueError, TypeError):
            raise ImageStoreIntegrityError("Stored Lila image data failed authentication") from None

    def _encode_json(self, payload: dict[str, Any], aad: bytes) -> tuple[bytes, bytes]:
        raw = bytearray(json.dumps(
            payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True
        ).encode("utf-8"))
        try:
            if len(raw) > self._MAX_PROVIDER_CONFIG_BYTES:
                raise ValueError("Lila image payload exceeds the storage limit")
            return self._encrypt(bytes(raw), aad)
        finally:
            raw[:] = bytes(len(raw))

    def _decode_json(self, nonce: bytes, ciphertext: bytes, aad: bytes) -> dict[str, Any]:
        raw = bytearray(self._decrypt(nonce, ciphertext, aad))
        try:
            value = json.loads(raw.decode("utf-8"))
            if not isinstance(value, dict):
                raise TypeError("Stored image payload must be an object")
            return value
        except ImageStoreIntegrityError:
            raise
        except Exception:
            raise ImageStoreIntegrityError("Stored Lila image payload failed validation") from None
        finally:
            raw[:] = bytes(len(raw))

    async def save_provider_config(self, *, principal_id: str, config: dict[str, Any]) -> None:
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        provider_key = self._resource_key("image_provider", "comfyui", 64)
        nonce, ciphertext = self._encode_json(
            config, self._aad("provider", owner_key, provider_key)
        )
        await asyncio.to_thread(
            self._save_provider_config_sync, owner_key, provider_key, nonce, ciphertext
        )

    def _save_provider_config_sync(self, owner_key, provider_key, nonce, ciphertext):
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                """INSERT INTO lila_image_providers(owner_key, provider_key, nonce, ciphertext, updated_at_ms)
                   VALUES(?, ?, ?, ?, ?)
                   ON CONFLICT(owner_key, provider_key) DO UPDATE SET
                       nonce=excluded.nonce, ciphertext=excluded.ciphertext,
                       updated_at_ms=excluded.updated_at_ms""",
                (owner_key, provider_key, nonce, ciphertext, int(time.time() * 1000)),
            )
            connection.execute("COMMIT")
        except BaseException:
            if connection.in_transaction:
                connection.execute("ROLLBACK")
            raise
        finally:
            connection.close()

    async def get_provider_config(self, *, principal_id: str) -> dict[str, Any] | None:
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        provider_key = self._resource_key("image_provider", "comfyui", 64)
        return await asyncio.to_thread(self._get_provider_config_sync, owner_key, provider_key)

    def _get_provider_config_sync(self, owner_key, provider_key):
        connection = self._connect()
        try:
            row = connection.execute(
                "SELECT nonce, ciphertext FROM lila_image_providers WHERE owner_key=? AND provider_key=?",
                (owner_key, provider_key),
            ).fetchone()
        finally:
            connection.close()
        if row is None:
            return None
        return self._decode_json(
            row["nonce"], row["ciphertext"], self._aad("provider", owner_key, provider_key)
        )

    async def create_job(
        self,
        *,
        principal_id: str,
        conversation_id: str,
        idempotency_key: str,
        request: dict[str, Any],
        provider_config: dict[str, Any],
    ) -> tuple[dict[str, Any], bool]:
        await self.initialize()
        if not isinstance(idempotency_key, str) or len(idempotency_key) > 128:
            raise ValueError("Image idempotency key is invalid")
        owner_key = self._owner_key(principal_id)
        conversation_key = self._resource_key("conversation", conversation_id, 128)
        request_key = self._resource_key("image_request", idempotency_key, 128)
        job_id = str(uuid4())
        provider_prompt_id = str(uuid5(NAMESPACE_URL, f"lila-image:{job_id}"))
        digest_payload = {"conversation_id": conversation_id, **request}
        digest_bytes = json.dumps(
            digest_payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True
        ).encode("utf-8")
        request_digest = hmac.new(
            self._index_key, b"image-request\0" + digest_bytes, hashlib.sha256
        ).hexdigest()
        payload = {
            **request,
            "job_id": job_id,
            "conversation_id": conversation_id,
            "provider_prompt_id": provider_prompt_id,
            "provider_config": provider_config,
        }
        job_key = self._resource_key("image_job", job_id, 64)
        nonce, ciphertext = self._encode_json(
            payload, self._aad("job", owner_key, job_key, request_key)
        )
        return await asyncio.to_thread(
            self._create_job_sync,
            owner_key, job_key, conversation_key, request_key, request_digest,
            nonce, ciphertext,
        )

    def _decode_job_row(self, owner_key: str, row: sqlite3.Row) -> dict[str, Any]:
        payload = self._decode_json(
            row["nonce"], row["ciphertext"],
            self._aad("job", owner_key, str(row["job_key"]), str(row["request_key"])),
        )
        asset = None
        if row["asset_sha256"] is not None:
            asset = {
                "mime_type": str(row["asset_mime_type"]),
                "width": int(row["asset_width"]),
                "height": int(row["asset_height"]),
                "byte_size": int(row["asset_byte_size"]),
                "sha256": str(row["asset_sha256"]),
            }
        return {
            **payload,
            "state": str(row["state"]),
            "created_at_epoch_millis": int(row["created_at_ms"]),
            "updated_at_epoch_millis": int(row["updated_at_ms"]),
            "asset": asset,
            "error_code": payload.get("error_code"),
        }

    def _job_select(self) -> str:
        return (
            "SELECT j.*, a.mime_type AS asset_mime_type, a.width AS asset_width, "
            "a.height AS asset_height, a.byte_size AS asset_byte_size, a.sha256 AS asset_sha256 "
            "FROM lila_image_jobs j LEFT JOIN lila_image_assets a "
            "ON a.owner_key=j.owner_key AND a.job_key=j.job_key "
        )

    def _create_job_sync(self, owner_key, job_key, conversation_key, request_key,
                         request_digest, nonce, ciphertext):
        now = int(time.time() * 1000)
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            existing = connection.execute(
                self._job_select() + "WHERE j.owner_key=? AND j.request_key=?",
                (owner_key, request_key),
            ).fetchone()
            if existing is not None:
                if not hmac.compare_digest(str(existing["request_digest"]), request_digest):
                    raise ImageIdempotencyConflict("Image idempotency key was reused for another request")
                result = self._decode_job_row(owner_key, existing)
                connection.execute("COMMIT")
                return result, False
            connection.execute(
                """INSERT INTO lila_image_jobs(
                    owner_key, job_key, conversation_key, request_key, request_digest,
                    state, nonce, ciphertext, created_at_ms, updated_at_ms
                ) VALUES(?, ?, ?, ?, ?, 'prepared', ?, ?, ?, ?)""",
                (owner_key, job_key, conversation_key, request_key, request_digest,
                 nonce, ciphertext, now, now),
            )
            row = connection.execute(
                self._job_select() + "WHERE j.owner_key=? AND j.job_key=?",
                (owner_key, job_key),
            ).fetchone()
            result = self._decode_job_row(owner_key, row)
            connection.execute("COMMIT")
            return result, True
        except BaseException:
            if connection.in_transaction:
                connection.execute("ROLLBACK")
            raise
        finally:
            connection.close()

    async def claim_submission(self, *, principal_id: str, job_id: str) -> bool:
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        job_key = self._resource_key("image_job", job_id, 64)
        return await asyncio.to_thread(self._claim_submission_sync, owner_key, job_key)

    def _claim_submission_sync(self, owner_key, job_key):
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            cursor = connection.execute(
                "UPDATE lila_image_jobs SET state='submitting', updated_at_ms=? "
                "WHERE owner_key=? AND job_key=? AND state='prepared'",
                (int(time.time() * 1000), owner_key, job_key),
            )
            connection.execute("COMMIT")
            return cursor.rowcount == 1
        except BaseException:
            if connection.in_transaction:
                connection.execute("ROLLBACK")
            raise
        finally:
            connection.close()

    async def update_job(
        self, *, principal_id: str, job_id: str, state: str, **payload_updates: Any
    ) -> dict[str, Any]:
        await self.initialize()
        if state not in self._STATES:
            raise ValueError("Image job state is invalid")
        owner_key = self._owner_key(principal_id)
        job_key = self._resource_key("image_job", job_id, 64)
        return await asyncio.to_thread(
            self._update_job_sync, owner_key, job_key, state, payload_updates
        )

    def _update_job_sync(self, owner_key, job_key, state, payload_updates):
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT * FROM lila_image_jobs WHERE owner_key=? AND job_key=?",
                (owner_key, job_key),
            ).fetchone()
            if row is None:
                raise ImageJobNotFound("Image job not found")
            current = str(row["state"])
            if state != current and state not in self._TRANSITIONS[current]:
                raise ValueError("Image job state transition is invalid")
            payload = self._decode_json(
                row["nonce"], row["ciphertext"],
                self._aad("job", owner_key, job_key, str(row["request_key"])),
            )
            payload.update(payload_updates)
            nonce, ciphertext = self._encode_json(
                payload, self._aad("job", owner_key, job_key, str(row["request_key"]))
            )
            now = int(time.time() * 1000)
            connection.execute(
                "UPDATE lila_image_jobs SET state=?, nonce=?, ciphertext=?, updated_at_ms=? "
                "WHERE owner_key=? AND job_key=?",
                (state, nonce, ciphertext, now, owner_key, job_key),
            )
            updated = connection.execute(
                self._job_select() + "WHERE j.owner_key=? AND j.job_key=?",
                (owner_key, job_key),
            ).fetchone()
            result = self._decode_job_row(owner_key, updated)
            connection.execute("COMMIT")
            return result
        except BaseException:
            if connection.in_transaction:
                connection.execute("ROLLBACK")
            raise
        finally:
            connection.close()

    async def get_job(self, *, principal_id: str, job_id: str) -> dict[str, Any] | None:
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        job_key = self._resource_key("image_job", job_id, 64)
        return await asyncio.to_thread(self._get_job_sync, owner_key, job_key)

    def _get_job_sync(self, owner_key, job_key):
        connection = self._connect()
        try:
            row = connection.execute(
                self._job_select() + "WHERE j.owner_key=? AND j.job_key=?",
                (owner_key, job_key),
            ).fetchone()
        finally:
            connection.close()
        return None if row is None else self._decode_job_row(owner_key, row)

    async def list_jobs(
        self, *, principal_id: str, conversation_id: str, limit: int = 50
    ) -> tuple[dict[str, Any], ...]:
        jobs, _ = await self.list_jobs_page(
            principal_id=principal_id,
            conversation_id=conversation_id,
            limit=limit,
        )
        return jobs

    async def list_jobs_page(
        self, *, principal_id: str, conversation_id: str, limit: int = 50,
        cursor: str | None = None,
    ) -> tuple[tuple[dict[str, Any], ...], str | None]:
        await self.initialize()
        if not 1 <= limit <= 50:
            raise ValueError("Image history limit is invalid")
        owner_key = self._owner_key(principal_id)
        conversation_key = self._resource_key("conversation", conversation_id, 128)
        cursor_position = self._decode_history_cursor(owner_key, cursor)
        jobs, has_more = await asyncio.to_thread(
            self._list_jobs_page_sync, owner_key, conversation_key, limit, cursor_position
        )
        next_cursor = (
            self._encode_history_cursor(owner_key, jobs[-1])
            if has_more and jobs
            else None
        )
        return jobs, next_cursor

    def _list_jobs_page_sync(self, owner_key, conversation_key, limit, cursor_position):
        query = self._job_select() + "WHERE j.owner_key=? AND j.conversation_key=? "
        parameters: list[Any] = [owner_key, conversation_key]
        if cursor_position is not None:
            created_at_ms, job_key = cursor_position
            query += (
                "AND (j.created_at_ms < ? OR "
                "(j.created_at_ms = ? AND j.job_key > ?)) "
            )
            parameters.extend((created_at_ms, created_at_ms, job_key))
        query += "ORDER BY j.created_at_ms DESC, j.job_key ASC LIMIT ?"
        parameters.append(limit + 1)
        connection = self._connect()
        try:
            rows = connection.execute(query, tuple(parameters)).fetchall()
        finally:
            connection.close()
        has_more = len(rows) > limit
        page_rows = rows[:limit]
        return (
            tuple(self._decode_job_row(owner_key, row) for row in page_rows),
            has_more,
        )

    async def save_asset(
        self, *, principal_id: str, job_id: str, mime_type: str,
        width: int, height: int, image_bytes: bytes,
    ) -> dict[str, Any]:
        await self.initialize()
        if mime_type != "image/png" or not 1 <= width <= 8192 or not 1 <= height <= 8192:
            raise ValueError("Generated image metadata is invalid")
        if not isinstance(image_bytes, bytes) or not 24 <= len(image_bytes) <= self._MAX_IMAGE_BYTES:
            raise ValueError("Generated image size is invalid")
        if (
            image_bytes[:8] != b"\x89PNG\r\n\x1a\n"
            or image_bytes[12:16] != b"IHDR"
            or int.from_bytes(image_bytes[16:20], "big") != width
            or int.from_bytes(image_bytes[20:24], "big") != height
        ):
            raise ValueError("Generated image payload does not match its PNG metadata")
        digest = hashlib.sha256(image_bytes).hexdigest()
        owner_key = self._owner_key(principal_id)
        job_key = self._resource_key("image_job", job_id, 64)
        nonce, ciphertext = self._encrypt(
            image_bytes, self._aad("asset", owner_key, job_key)
        )
        return await asyncio.to_thread(
            self._save_asset_sync, owner_key, job_key, mime_type, width, height,
            len(image_bytes), digest, nonce, ciphertext,
        )

    def _save_asset_sync(self, owner_key, job_key, mime_type, width, height,
                         byte_size, digest, nonce, ciphertext):
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            job = connection.execute(
                "SELECT state FROM lila_image_jobs WHERE owner_key=? AND job_key=?",
                (owner_key, job_key),
            ).fetchone()
            if job is None:
                raise ImageJobNotFound("Image job not found")
            if str(job["state"]) not in {"provider_completed", "result_pending", "completed"}:
                raise ValueError("Image job is not ready to persist a result")
            existing = connection.execute(
                "SELECT sha256 FROM lila_image_assets WHERE owner_key=? AND job_key=?",
                (owner_key, job_key),
            ).fetchone()
            if existing is not None and not hmac.compare_digest(str(existing["sha256"]), digest):
                raise ImageStoreIntegrityError("Image job already has a different result")
            if existing is None:
                connection.execute(
                    """INSERT INTO lila_image_assets(
                        owner_key, job_key, mime_type, width, height, byte_size,
                        sha256, nonce, ciphertext, created_at_ms
                    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    (owner_key, job_key, mime_type, width, height, byte_size,
                     digest, nonce, ciphertext, int(time.time() * 1000)),
                )
            connection.execute(
                "UPDATE lila_image_jobs SET state='completed', updated_at_ms=? "
                "WHERE owner_key=? AND job_key=?",
                (int(time.time() * 1000), owner_key, job_key),
            )
            connection.execute("COMMIT")
            return {
                "mime_type": mime_type, "width": width, "height": height,
                "byte_size": byte_size, "sha256": digest,
            }
        except BaseException:
            if connection.in_transaction:
                connection.execute("ROLLBACK")
            raise
        finally:
            connection.close()

    async def get_asset(self, *, principal_id: str, job_id: str) -> dict[str, Any] | None:
        await self.initialize()
        owner_key = self._owner_key(principal_id)
        job_key = self._resource_key("image_job", job_id, 64)
        return await asyncio.to_thread(self._get_asset_sync, owner_key, job_key)

    def _get_asset_sync(self, owner_key, job_key):
        connection = self._connect()
        try:
            row = connection.execute(
                "SELECT * FROM lila_image_assets WHERE owner_key=? AND job_key=?",
                (owner_key, job_key),
            ).fetchone()
        finally:
            connection.close()
        if row is None:
            return None
        image_bytes = self._decrypt(
            row["nonce"], row["ciphertext"], self._aad("asset", owner_key, job_key)
        )
        if len(image_bytes) != int(row["byte_size"]) or not hmac.compare_digest(
            hashlib.sha256(image_bytes).hexdigest(), str(row["sha256"])
        ):
            raise ImageStoreIntegrityError("Stored image asset failed integrity validation")
        return {
            "mime_type": str(row["mime_type"]),
            "width": int(row["width"]),
            "height": int(row["height"]),
            "byte_size": int(row["byte_size"]),
            "sha256": str(row["sha256"]),
            "bytes": image_bytes,
        }
