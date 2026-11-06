from dataclasses import dataclass
import os


@dataclass(frozen=True)
class Settings:
    openai_api_key: str
    auth_jwks_url: str
    auth_issuer: str
    auth_audience: str
    safety_identifier_hmac_key: str
    allowed_voices: frozenset[str]
    allowed_character_ids: frozenset[str]
    realtime_model: str = "gpt-realtime"
    client_secret_ttl_seconds: int = 600
    per_user_requests_per_minute: int = 5
    environment: str = "development"
    redis_url: str | None = None

    @classmethod
    def from_env(cls) -> "Settings":
        required = (
            "OPENAI_API_KEY",
            "AUTH_JWKS_URL",
            "AUTH_ISSUER",
            "AUTH_AUDIENCE",
            "SAFETY_IDENTIFIER_HMAC_KEY",
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

        environment = os.getenv("GALAXY_ENVIRONMENT", "development").strip().lower()
        if environment not in {"development", "test", "production"}:
            raise RuntimeError("GALAXY_ENVIRONMENT must be development, test, or production")

        redis_url = os.getenv("REDIS_URL") or None
        if environment == "production" and not redis_url:
            raise RuntimeError("REDIS_URL is required when GALAXY_ENVIRONMENT=production")

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
            openai_api_key=os.environ["OPENAI_API_KEY"],
            auth_jwks_url=os.environ["AUTH_JWKS_URL"],
            auth_issuer=os.environ["AUTH_ISSUER"],
            auth_audience=os.environ["AUTH_AUDIENCE"],
            safety_identifier_hmac_key=os.environ["SAFETY_IDENTIFIER_HMAC_KEY"],
            allowed_voices=voices,
            allowed_character_ids=characters,
            realtime_model=os.getenv("REALTIME_MODEL", "gpt-realtime"),
            client_secret_ttl_seconds=ttl,
            per_user_requests_per_minute=request_limit,
            environment=environment,
            redis_url=redis_url,
        )
