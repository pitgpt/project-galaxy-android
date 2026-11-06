from collections import defaultdict, deque
from hashlib import sha256
import hmac
import time
from threading import Lock
from typing import Any

import jwt
from jwt import PyJWKClient
from fastapi import HTTPException, status

from .config import Settings


class JwtAuthenticator:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.jwks = PyJWKClient(settings.auth_jwks_url)

    def authenticate(self, token: str) -> str:
        try:
            signing_key = self.jwks.get_signing_key_from_jwt(token)
            claims: dict[str, Any] = jwt.decode(
                token,
                signing_key.key,
                algorithms=["RS256"],
                audience=self.settings.auth_audience,
                issuer=self.settings.auth_issuer,
                options={"require": ["exp", "iat", "sub"]},
            )
            subject = claims.get("sub")
            if not isinstance(subject, str) or not subject.strip():
                raise ValueError("Missing subject")
            return subject
        except Exception as exc:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid or expired access token",
                headers={"WWW-Authenticate": "Bearer"},
            ) from exc


def safety_identifier(user_id: str, secret: str) -> str:
    digest = hmac.new(secret.encode(), user_id.encode(), sha256).hexdigest()
    return "galaxy_" + digest[:48]


class InMemoryRateLimiter:
    """Development/test limiter. Never use this for a production deployment."""

    def __init__(self, limit: int, window_seconds: int = 60):
        self.limit = limit
        self.window_seconds = window_seconds
        self.events: dict[str, deque[float]] = defaultdict(deque)
        self.lock = Lock()

    async def check(self, key: str) -> None:
        now = time.monotonic()
        with self.lock:
            events = self.events[key]
            while events and now - events[0] >= self.window_seconds:
                events.popleft()
            if len(events) >= self.limit:
                raise HTTPException(status_code=429, detail="Realtime session rate limit exceeded")
            events.append(now)


class RedisRateLimiter:
    """Atomic shared fixed-window limiter. Redis errors fail closed."""

    _SCRIPT = """
    local current = redis.call('INCR', KEYS[1])
    if current == 1 then
        redis.call('PEXPIRE', KEYS[1], ARGV[1])
    end
    local ttl = redis.call('PTTL', KEYS[1])
    return {current, ttl}
    """

    def __init__(self, redis_client: Any, limit: int, window_seconds: int = 60):
        self.redis = redis_client
        self.limit = limit
        self.window_milliseconds = window_seconds * 1000

    async def check(self, user_id: str) -> None:
        key_digest = sha256(user_id.encode("utf-8")).hexdigest()
        redis_key = "galaxy:realtime:rate:" + key_digest
        try:
            result = await self.redis.eval(
                self._SCRIPT, 1, redis_key, self.window_milliseconds
            )
            count = int(result[0])
        except Exception as exc:
            raise HTTPException(
                status_code=503,
                detail="Realtime session service temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from exc

        if count > self.limit:
            raise HTTPException(
                status_code=429,
                detail="Realtime session rate limit exceeded",
                headers={"Retry-After": "60"},
            )
