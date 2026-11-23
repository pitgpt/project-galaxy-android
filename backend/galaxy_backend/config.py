from dataclasses import dataclass, field
import os
from pathlib import Path

from .lila_core.openai_compatible import DEFAULT_LILA_SYSTEM_PROMPT


def _read_secret_setting(name: str, *, max_bytes: int) -> tuple[str | None, bool]:
    """Read a secret from the environment or a mounted secret file, never both."""
    file_name = f"{name}_FILE"
    direct_value = os.getenv(name) or None
    file_path = os.getenv(file_name) or None
    if direct_value is not None and file_path is not None:
        raise RuntimeError(f"Set only one of {name} or {file_name}")
    if file_path is None:
        return direct_value, False

    path = Path(file_path)
    if not path.is_absolute():
        raise RuntimeError(f"{file_name} must be an absolute path")
    try:
        with path.open("rb") as secret_file:
            raw = secret_file.read(max_bytes + 1)
    except OSError:
        raise RuntimeError(f"{file_name} could not be read") from None
    if len(raw) > max_bytes:
        raise RuntimeError(f"{file_name} exceeds the maximum allowed size")
    try:
        value = raw.decode("ascii").strip()
    except UnicodeDecodeError:
        raise RuntimeError(f"{file_name} must contain ASCII text") from None
    return value or None, True


@dataclass(frozen=True)
class Settings:
    # Realtime/voice credentials are optional so a local-only Lila deployment
    # does not need an unrelated cloud voice provider configured.
    openai_api_key: str | None = field(repr=False)
    auth_jwks_url: str
    auth_issuer: str
    auth_audience: str
    safety_identifier_hmac_key: str | None = field(repr=False)
    allowed_voices: frozenset[str]
    allowed_character_ids: frozenset[str]
    realtime_model: str = "gpt-realtime"
    client_secret_ttl_seconds: int = 600
    per_user_requests_per_minute: int = 5
    per_user_sessions_per_day: int = 100
    max_active_sessions: int = 2
    session_lease_seconds: int = 90
    environment: str = "development"
    redis_url: str | None = field(default=None, repr=False)
    lila_turns_per_minute: int = 20
    lila_turns_per_day: int = 500
    lila_provider_id: str = "default"
    lila_provider_base_url: str | None = None
    lila_provider_model_id: str | None = None
    lila_provider_api_key: str | None = field(default=None, repr=False)
    lila_provider_timeout_seconds: float = 30.0
    lila_database_path: str | None = None
    lila_storage_encryption_key: bytes | None = field(default=None, repr=False)
    lila_system_prompt: str = DEFAULT_LILA_SYSTEM_PROMPT

    @property
    def lila_storage_is_configured(self) -> bool:
        """Encrypted persistent storage can host the owner-scoped provider catalog."""
        return bool(
            self.lila_database_path
            and self.lila_storage_encryption_key is not None
        )

    @property
    def lila_provider_is_configured(self) -> bool:
        """The legacy operator-selected provider is optional when using the catalog."""
        return bool(self.lila_provider_base_url and self.lila_provider_model_id)

    @property
    def lila_is_configured(self) -> bool:
        return self.lila_storage_is_configured and self.lila_provider_is_configured

    @property
    def lila_provider_configuration_requested(self) -> bool:
        return any((
            self.lila_provider_base_url,
            self.lila_provider_model_id,
            self.lila_provider_api_key,
        ))

    @property
    def lila_configuration_requested(self) -> bool:
        return any((
            self.lila_provider_configuration_requested,
            self.lila_database_path,
            self.lila_storage_encryption_key is not None,
        ))

    @classmethod
    def from_env(cls) -> "Settings":
        required = (
            "AUTH_JWKS_URL",
            "AUTH_ISSUER",
            "AUTH_AUDIENCE",
        )
        missing = [name for name in required if not os.getenv(name)]
        if missing:
            raise RuntimeError("Missing required environment configuration: " + ", ".join(missing))

        ttl = int(os.getenv("REALTIME_CLIENT_SECRET_TTL_SECONDS", "600"))
        if not 10 <= ttl <= 7200:
            raise RuntimeError("REALTIME_CLIENT_SECRET_TTL_SECONDS must be between 10 and 7200")

        request_limit = int(os.getenv("REALTIME_REQUESTS_PER_MINUTE", "5"))
        if request_limit < 1:
            raise RuntimeError("REALTIME_REQUESTS_PER_MINUTE must be at least 1")

        daily_limit = int(os.getenv("REALTIME_SESSIONS_PER_DAY", "100"))
        if daily_limit < 1:
            raise RuntimeError("REALTIME_SESSIONS_PER_DAY must be at least 1")

        lila_turns_per_minute = int(os.getenv("LILA_TURNS_PER_MINUTE", "20"))
        if not 1 <= lila_turns_per_minute <= 1000:
            raise RuntimeError("LILA_TURNS_PER_MINUTE must be between 1 and 1000")
        lila_turns_per_day = int(os.getenv("LILA_TURNS_PER_DAY", "500"))
        if not 1 <= lila_turns_per_day <= 100000:
            raise RuntimeError("LILA_TURNS_PER_DAY must be between 1 and 100000")

        max_active = int(os.getenv("REALTIME_MAX_ACTIVE_SESSIONS", "2"))
        if max_active < 1:
            raise RuntimeError("REALTIME_MAX_ACTIVE_SESSIONS must be at least 1")
        lease_seconds = int(os.getenv("REALTIME_SESSION_LEASE_SECONDS", "90"))
        if lease_seconds < 30:
            raise RuntimeError("REALTIME_SESSION_LEASE_SECONDS must be at least 30")

        environment = os.getenv("GALAXY_ENVIRONMENT", "development").strip().lower()
        if environment not in {"development", "test", "production"}:
            raise RuntimeError("GALAXY_ENVIRONMENT must be development, test, or production")

        redis_url = os.getenv("REDIS_URL") or None
        if environment == "production" and not redis_url:
            raise RuntimeError("REDIS_URL is required when GALAXY_ENVIRONMENT=production")

        lila_provider_base_url = os.getenv("LILA_PROVIDER_BASE_URL") or None
        lila_provider_model_id = os.getenv("LILA_MODEL_ID") or None
        lila_provider_api_key, _ = _read_secret_setting(
            "LILA_PROVIDER_API_KEY", max_bytes=4096
        )
        lila_database_path = os.getenv("LILA_DATABASE_PATH") or None
        storage_key_text, storage_key_file_requested = _read_secret_setting(
            "LILA_STORAGE_ENCRYPTION_KEY", max_bytes=128
        )

        lila_storage_encryption_key = None
        if storage_key_text is not None:
            if len(storage_key_text) != 64:
                raise RuntimeError("LILA_STORAGE_ENCRYPTION_KEY must be a 64-character hexadecimal value")
            try:
                lila_storage_encryption_key = bytes.fromhex(storage_key_text)
            except ValueError:
                raise RuntimeError("LILA_STORAGE_ENCRYPTION_KEY must be a 64-character hexadecimal value") from None
            if len(lila_storage_encryption_key) != 32:
                raise RuntimeError("LILA_STORAGE_ENCRYPTION_KEY must decode to exactly 32 bytes")

        if lila_database_path is not None and not Path(lila_database_path).is_absolute():
            raise RuntimeError("LILA_DATABASE_PATH must be an absolute path on persistent local storage")

        try:
            lila_provider_timeout_seconds = float(os.getenv("LILA_PROVIDER_TIMEOUT_SECONDS", "30"))
        except ValueError:
            raise RuntimeError("LILA_PROVIDER_TIMEOUT_SECONDS must be a number") from None
        if not 1 <= lila_provider_timeout_seconds <= 120:
            raise RuntimeError("LILA_PROVIDER_TIMEOUT_SECONDS must be between 1 and 120")

        lila_provider_id = os.getenv("LILA_PROVIDER_ID", "default").strip()
        lila_system_prompt = os.getenv("LILA_SYSTEM_PROMPT", DEFAULT_LILA_SYSTEM_PROMPT)
        if not lila_system_prompt.strip() or len(lila_system_prompt) > 8000:
            raise RuntimeError("LILA_SYSTEM_PROMPT must contain between 1 and 8000 characters")

        lila_storage_requested = any((
            lila_database_path,
            storage_key_text,
            storage_key_file_requested,
        ))
        lila_storage_complete = bool(
            lila_database_path and lila_storage_encryption_key is not None
        )
        lila_provider_requested = any((
            lila_provider_base_url,
            lila_provider_model_id,
            lila_provider_api_key,
        ))
        lila_provider_complete = bool(
            lila_provider_base_url and lila_provider_model_id
        )
        if lila_storage_requested and not lila_storage_complete:
            raise RuntimeError(
                "Lila provider storage is incomplete; set LILA_DATABASE_PATH "
                "and LILA_STORAGE_ENCRYPTION_KEY or LILA_STORAGE_ENCRYPTION_KEY_FILE together"
            )
        if lila_provider_requested and not (
            lila_provider_complete and lila_storage_complete
        ):
            raise RuntimeError(
                "Lila configuration is incomplete: operator provider settings require LILA_PROVIDER_BASE_URL, "
                "LILA_MODEL_ID, LILA_DATABASE_PATH and LILA_STORAGE_ENCRYPTION_KEY"
            )
        if environment == "production" and not lila_storage_complete:
            raise RuntimeError(
                "Production requires a configured Lila model provider or owner-managed catalog and encrypted persistent storage"
            )

        voices = frozenset(
            item.strip()
            for item in os.getenv("GALAXY_ALLOWED_VOICES", "alloy,ash,ballad,coral,echo,sage,shimmer,verse").split(",")
            if item.strip()
        )
        if not voices:
            raise RuntimeError("GALAXY_ALLOWED_VOICES must contain at least one voice")

        characters = frozenset(
            item.strip()
            for item in os.getenv("GALAXY_ALLOWED_CHARACTER_IDS", "galaxy.default").split(",")
            if item.strip()
        )
        if not characters:
            raise RuntimeError("GALAXY_ALLOWED_CHARACTER_IDS must contain at least one character")

        return cls(
            openai_api_key=os.getenv("OPENAI_API_KEY") or None,
            auth_jwks_url=os.environ["AUTH_JWKS_URL"],
            auth_issuer=os.environ["AUTH_ISSUER"],
            auth_audience=os.environ["AUTH_AUDIENCE"],
            safety_identifier_hmac_key=os.getenv("SAFETY_IDENTIFIER_HMAC_KEY") or None,
            allowed_voices=voices,
            allowed_character_ids=characters,
            realtime_model=os.getenv("REALTIME_MODEL", "gpt-realtime"),
            client_secret_ttl_seconds=ttl,
            per_user_requests_per_minute=request_limit,
            per_user_sessions_per_day=daily_limit,
            max_active_sessions=max_active,
            session_lease_seconds=lease_seconds,
            environment=environment,
            redis_url=redis_url,
            lila_turns_per_minute=lila_turns_per_minute,
            lila_turns_per_day=lila_turns_per_day,
            lila_provider_id=lila_provider_id,
            lila_provider_base_url=lila_provider_base_url,
            lila_provider_model_id=lila_provider_model_id,
            lila_provider_api_key=lila_provider_api_key,
            lila_provider_timeout_seconds=lila_provider_timeout_seconds,
            lila_database_path=lila_database_path,
            lila_storage_encryption_key=lila_storage_encryption_key,
            lila_system_prompt=lila_system_prompt,
        )
