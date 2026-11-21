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
                algorithms=["RS256", "ES256"],
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

    def __init__(self, limit: int, daily_limit: int = 100, window_seconds: int = 60):
        self.limit = limit
        self.daily_limit = daily_limit
        self.window_seconds = window_seconds
        self.events: dict[str, deque[float]] = defaultdict(deque)
        self.daily_events: dict[tuple[str, int], int] = defaultdict(int)
        self.lock = Lock()

    async def check(self, key: str) -> None:
        now = time.monotonic()
        with self.lock:
            events = self.events[key]
            while events and now - events[0] >= self.window_seconds:
                events.popleft()
            if len(events) >= self.limit:
                raise HTTPException(status_code=429, detail="Realtime session rate limit exceeded", headers={"Retry-After": str(self.window_seconds)})
            daily_key = (key, int(time.time() // 86400))
            if self.daily_events[daily_key] >= self.daily_limit:
                raise HTTPException(status_code=429, detail="Daily realtime session credential limit exceeded", headers={"Retry-After": "86400"})
            self.daily_events[daily_key] += 1
            events.append(now)


class RedisRateLimiter:
    """Atomic shared fixed-window limiter. Redis errors fail closed."""

    _SCRIPT = """
    local minute_count = redis.call('INCR', KEYS[1])
    if minute_count == 1 then
        redis.call('PEXPIRE', KEYS[1], ARGV[1])
    end
    local daily_count = redis.call('INCR', KEYS[2])
    if daily_count == 1 then
        redis.call('PEXPIRE', KEYS[2], ARGV[2])
    end
    return {minute_count, daily_count}
    """

    def __init__(self, redis_client: Any, limit: int, daily_limit: int = 100, window_seconds: int = 60):
        self.redis = redis_client
        self.limit = limit
        self.daily_limit = daily_limit
        self.window_milliseconds = window_seconds * 1000
        self.daily_window_milliseconds = 24 * 60 * 60 * 1000

    async def check(self, user_id: str) -> None:
        key_digest = sha256(user_id.encode("utf-8")).hexdigest()
        minute_key = "galaxy:realtime:rate:" + key_digest
        daily_key = "galaxy:realtime:daily:" + key_digest
        try:
            result = await self.redis.eval(
                self._SCRIPT, 2, minute_key, daily_key,
                self.window_milliseconds, self.daily_window_milliseconds
            )
            minute_count, daily_count = int(result[0]), int(result[1])
        except Exception as exc:
            raise HTTPException(
                status_code=503,
                detail="Realtime session service temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from exc

        if minute_count > self.limit:
            raise HTTPException(
                status_code=429,
                detail="Realtime session rate limit exceeded",
                headers={"Retry-After": str(self.window_milliseconds // 1000)},
            )
        if daily_count > self.daily_limit:
            raise HTTPException(
                status_code=429,
                detail="Daily realtime session credential limit exceeded",
                headers={"Retry-After": "86400"},
            )


class InMemoryLilaTurnRateLimiter:
    """Process-local fixed-window limiter for development and tests only."""

    def __init__(
        self,
        limit: int,
        daily_limit: int,
        window_seconds: int = 60,
    ) -> None:
        if limit < 1 or daily_limit < 1 or window_seconds < 1:
            raise ValueError("Lila turn rate limits must be positive")
        self.limit = limit
        self.daily_limit = daily_limit
        self.window_seconds = window_seconds
        self._minute_counts: dict[tuple[str, int], int] = {}
        self._daily_counts: dict[tuple[str, int], int] = {}
        self._lock = Lock()

    async def check(self, user_id: str) -> None:
        if not isinstance(user_id, str) or not user_id.strip():
            raise ValueError("user_id must be a non-empty authenticated subject")

        now = time.time()
        minute_bucket = int(now // self.window_seconds)
        daily_bucket = int(now // 86400)
        user_key = sha256(user_id.encode("utf-8")).hexdigest()
        minute_key = (user_key, minute_bucket)
        daily_key = (user_key, daily_bucket)

        with self._lock:
            minute_count = self._minute_counts.get(minute_key, 0)
            daily_count = self._daily_counts.get(daily_key, 0)
            if minute_count >= self.limit:
                retry_after = max(1, int(self.window_seconds - (now % self.window_seconds)))
                raise HTTPException(
                    status_code=429,
                    detail="Lila turn rate limit exceeded",
                    headers={"Retry-After": str(retry_after)},
                )
            if daily_count >= self.daily_limit:
                retry_after = max(1, int(86400 - (now % 86400)))
                raise HTTPException(
                    status_code=429,
                    detail="Daily Lila turn limit exceeded",
                    headers={"Retry-After": str(retry_after)},
                )

            self._minute_counts[minute_key] = minute_count + 1
            self._daily_counts[daily_key] = daily_count + 1

            if len(self._minute_counts) > 10000:
                self._minute_counts = {
                    key: value
                    for key, value in self._minute_counts.items()
                    if key[1] >= minute_bucket - 1
                }
            if len(self._daily_counts) > 10000:
                self._daily_counts = {
                    key: value
                    for key, value in self._daily_counts.items()
                    if key[1] >= daily_bucket
                }


class RedisLilaTurnRateLimiter:
    """Atomic shared per-user Lila turn limits, separate from Realtime quotas."""

    _SCRIPT = """
    local minute_count = tonumber(redis.call('GET', KEYS[1]) or '0')
    local daily_count = tonumber(redis.call('GET', KEYS[2]) or '0')
    if minute_count >= tonumber(ARGV[3]) then
        return {minute_count, daily_count, 1}
    end
    if daily_count >= tonumber(ARGV[4]) then
        return {minute_count, daily_count, 2}
    end
    minute_count = redis.call('INCR', KEYS[1])
    if minute_count == 1 then
        redis.call('PEXPIRE', KEYS[1], ARGV[1])
    end
    daily_count = redis.call('INCR', KEYS[2])
    if daily_count == 1 then
        redis.call('PEXPIRE', KEYS[2], ARGV[2])
    end
    return {minute_count, daily_count, 0}
    """

    def __init__(
        self,
        redis_client: Any,
        limit: int,
        daily_limit: int,
        window_seconds: int = 60,
    ) -> None:
        if limit < 1 or daily_limit < 1 or window_seconds < 1:
            raise ValueError("Lila turn rate limits must be positive")
        self.redis = redis_client
        self.limit = limit
        self.daily_limit = daily_limit
        self.window_seconds = window_seconds

    async def check(self, user_id: str) -> None:
        if not isinstance(user_id, str) or not user_id.strip():
            raise ValueError("user_id must be a non-empty authenticated subject")

        now = time.time()
        minute_bucket = int(now // self.window_seconds)
        daily_bucket = int(now // 86400)
        user_digest = sha256(user_id.encode("utf-8")).hexdigest()
        # The shared Redis hash tag keeps both keys in one Redis Cluster slot.
        minute_key = f"galaxy:lila:turns:{{{user_digest}}}:minute:{minute_bucket}"
        daily_key = f"galaxy:lila:turns:{{{user_digest}}}:day:{daily_bucket}"
        minute_ttl_ms = max(
            1, int(((minute_bucket + 1) * self.window_seconds - now) * 1000)
        )
        daily_ttl_ms = max(
            1, int(((daily_bucket + 1) * 86400 - now) * 1000)
        )

        try:
            result = await self.redis.eval(
                self._SCRIPT,
                2,
                minute_key,
                daily_key,
                minute_ttl_ms,
                daily_ttl_ms,
                self.limit,
                self.daily_limit,
            )
            minute_count, daily_count, decision = (int(value) for value in result)
        except Exception as exc:
            raise HTTPException(
                status_code=503,
                detail="Lila turn service temporarily unavailable",
                headers={"Retry-After": "5"},
            ) from exc

        if decision == 1:
            retry_after = max(1, int(self.window_seconds - (now % self.window_seconds)))
            raise HTTPException(
                status_code=429,
                detail="Lila turn rate limit exceeded",
                headers={"Retry-After": str(retry_after)},
            )
        if decision == 2:
            retry_after = max(1, int(86400 - (now % 86400)))
            raise HTTPException(
                status_code=429,
                detail="Daily Lila turn limit exceeded",
                headers={"Retry-After": str(retry_after)},
            )
        if decision != 0 or minute_count < 1 or daily_count < 1:
            raise HTTPException(
                status_code=503,
                detail="Lila turn service temporarily unavailable",
                headers={"Retry-After": "5"},
            )
