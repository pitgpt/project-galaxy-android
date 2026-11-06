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
    realtime_model: str = "gpt-realtime"
    client_secret_ttl_seconds: int = 600
    per_user_requests_per_minute: int = 5

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

        voices = frozenset(
            item.strip()
            for item in os.getenv("GALAXY_ALLOWED_VOICES", "alloy,ash,ballad,coral,echo,sage,shimmer,verse").split(",")
            if item.strip()
        )
        if not voices:
            raise RuntimeError("GALAXY_ALLOWED_VOICES must contain at least one voice")

        return cls(
            openai_api_key=os.environ["OPENAI_API_KEY"],
            auth_jwks_url=os.environ["AUTH_JWKS_URL"],
            auth_issuer=os.environ["AUTH_ISSUER"],
            auth_audience=os.environ["AUTH_AUDIENCE"],
            safety_identifier_hmac_key=os.environ["SAFETY_IDENTIFIER_HMAC_KEY"],
            allowed_voices=voices,
            realtime_model=os.getenv("REALTIME_MODEL", "gpt-realtime"),
            client_secret_ttl_seconds=ttl,
            per_user_requests_per_minute=int(os.getenv("REALTIME_REQUESTS_PER_MINUTE", "5")),
        )
