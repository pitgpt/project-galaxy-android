import asyncio

import pytest
from fastapi import HTTPException

from galaxy_backend.security import (
    InMemoryLilaTurnRateLimiter,
    RedisLilaTurnRateLimiter,
)


class FakeRedis:
    def __init__(self):
        self.counts = {}
        self.calls = []

    async def eval(self, script, number_of_keys, *args):
        assert number_of_keys == 2
        minute_key, daily_key, minute_ttl, daily_ttl, limit, daily_limit = args
        self.calls.append((minute_key, daily_key, minute_ttl, daily_ttl))
        minute_count = self.counts.get(minute_key, 0)
        daily_count = self.counts.get(daily_key, 0)
        if minute_count >= int(limit):
            return [minute_count, daily_count, 1]
        if daily_count >= int(daily_limit):
            return [minute_count, daily_count, 2]
        minute_count += 1
        daily_count += 1
        self.counts[minute_key] = minute_count
        self.counts[daily_key] = daily_count
        return [minute_count, daily_count, 0]


class BrokenRedis:
    async def eval(self, *args):
        raise OSError("redis-password-detail")


def run(coro):
    return asyncio.run(coro)


def test_in_memory_lila_limiter_is_scoped_per_authenticated_user():
    limiter = InMemoryLilaTurnRateLimiter(limit=1, daily_limit=10)

    run(limiter.check("user-a"))
    with pytest.raises(HTTPException) as error:
        run(limiter.check("user-a"))
    assert error.value.status_code == 429
    assert error.value.detail == "Lila turn rate limit exceeded"

    run(limiter.check("user-b"))


def test_in_memory_lila_limiter_enforces_daily_turn_limit():
    limiter = InMemoryLilaTurnRateLimiter(limit=10, daily_limit=1)

    run(limiter.check("user-a"))
    with pytest.raises(HTTPException) as error:
        run(limiter.check("user-a"))
    assert error.value.status_code == 429
    assert error.value.detail == "Daily Lila turn limit exceeded"


def test_redis_lila_limiter_uses_hashed_owner_keys_and_separate_namespace():
    redis = FakeRedis()
    limiter = RedisLilaTurnRateLimiter(redis, limit=1, daily_limit=10)

    run(limiter.check("user-a"))
    with pytest.raises(HTTPException) as error:
        run(limiter.check("user-a"))
    assert error.value.status_code == 429
    assert error.value.detail == "Lila turn rate limit exceeded"

    run(limiter.check("user-b"))
    first_minute_key, first_daily_key, minute_ttl, daily_ttl = redis.calls[0]
    assert first_minute_key.startswith("galaxy:lila:turns:minute:")
    assert first_daily_key.startswith("galaxy:lila:turns:day:")
    assert "user-a" not in first_minute_key
    assert "user-a" not in first_daily_key
    assert minute_ttl > 0
    assert daily_ttl > minute_ttl


def test_redis_lila_limiter_enforces_daily_turn_limit():
    redis = FakeRedis()
    limiter = RedisLilaTurnRateLimiter(redis, limit=10, daily_limit=1)

    run(limiter.check("user-a"))
    with pytest.raises(HTTPException) as error:
        run(limiter.check("user-a"))
    assert error.value.status_code == 429
    assert error.value.detail == "Daily Lila turn limit exceeded"


def test_redis_lila_limiter_fails_closed_when_redis_is_unavailable():
    limiter = RedisLilaTurnRateLimiter(BrokenRedis(), limit=10, daily_limit=100)

    with pytest.raises(HTTPException) as error:
        run(limiter.check("user-a"))
    assert error.value.status_code == 503
    assert "redis-password-detail" not in error.value.detail
