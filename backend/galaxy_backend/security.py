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
    """Single-process safety net. Production deployments must use shared Redis-backed limits."""

    def __init__(self, limit: int, window_seconds: int = 60):
        self.limit = limit
        self.window_seconds = window_seconds
        self.events: dict[str, deque[float]] = defaultdict(deque)
        self.lock = Lock()

    def check(self, key: str) -> None:
        now = time.monotonic()
        with self.lock:
            events = self.events[key]
            while events and now - events[0] >= self.window_seconds:
                events.popleft()
            if len(events) >= self.limit:
                raise HTTPException(status_code=429, detail="Realtime session rate limit exceeded")
            events.append(now)
