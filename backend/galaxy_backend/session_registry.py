from hashlib import sha256
import time
from typing import Any


class RedisSessionRegistry:
    """Atomic Redis leases for client-reported active sessions."""

    _RESERVE = """
    redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
    local active = redis.call('ZCARD', KEYS[1])
    if active >= tonumber(ARGV[3]) then return 0 end
    redis.call('ZADD', KEYS[1], ARGV[2], ARGV[4])
    redis.call('EXPIRE', KEYS[1], ARGV[5])
    return 1
    """
    _HEARTBEAT = """
    local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
    if not score then return 0 end
    if tonumber(score) <= tonumber(ARGV[2]) then
        redis.call('ZREM', KEYS[1], ARGV[1])
        return 0
    end
    redis.call('ZADD', KEYS[1], ARGV[3], ARGV[1])
    redis.call('EXPIRE', KEYS[1], ARGV[4])
    return 1
    """
    _CLOSE = "return redis.call('ZREM', KEYS[1], ARGV[1])"

    def __init__(self, redis_client: Any, max_active: int, lease_seconds: int = 90):
        self.redis = redis_client
        self.max_active = max_active
        self.lease_seconds = lease_seconds

    def _key(self, user_id: str) -> str:
        digest = sha256(user_id.encode("utf-8")).hexdigest()
        return "galaxy:realtime:sessions:" + digest

    async def reserve(self, user_id: str, session_id: str) -> bool:
        now = int(time.time() * 1000)
        expires = now + self.lease_seconds * 1000
        result = await self.redis.eval(
            self._RESERVE, 1, self._key(user_id), now, expires,
            self.max_active, session_id, self.lease_seconds * 2
        )
        return bool(int(result))

    async def heartbeat(self, user_id: str, session_id: str) -> bool:
        now = int(time.time() * 1000)
        expires = now + self.lease_seconds * 1000
        result = await self.redis.eval(
            self._HEARTBEAT, 1, self._key(user_id), session_id, now,
            expires, self.lease_seconds * 2
        )
        return bool(int(result))

    async def close(self, user_id: str, session_id: str) -> None:
        await self.redis.eval(self._CLOSE, 1, self._key(user_id), session_id)


class InMemorySessionRegistry:
    """Single-process development/test implementation; not for production."""

    def __init__(self, max_active: int, lease_seconds: int = 90):
        self.max_active = max_active
        self.lease_seconds = lease_seconds
        self.sessions: dict[str, dict[str, float]] = {}
        import asyncio
        self._lock = asyncio.Lock()

    async def reserve(self, user_id: str, session_id: str) -> bool:
        now = time.time()
        async with self._lock:
            sessions = self.sessions.setdefault(user_id, {})
            expired = [sid for sid, expiry in sessions.items() if expiry <= now]
            for sid in expired:
                sessions.pop(sid, None)
            if len(sessions) >= self.max_active:
                return False
            sessions[session_id] = now + self.lease_seconds
            return True

    async def heartbeat(self, user_id: str, session_id: str) -> bool:
        now = time.time()
        async with self._lock:
            sessions = self.sessions.setdefault(user_id, {})
            if sessions.get(session_id, 0) <= now:
                sessions.pop(session_id, None)
                return False
            sessions[session_id] = now + self.lease_seconds
            return True

    async def close(self, user_id: str, session_id: str) -> None:
        async with self._lock:
            self.sessions.setdefault(user_id, {}).pop(session_id, None)
