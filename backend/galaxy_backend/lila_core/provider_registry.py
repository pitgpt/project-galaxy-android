"""Validation helpers for owner-managed Lila provider records.

This module validates configuration at rest. It deliberately does not perform
outbound requests: user-managed endpoints must not be activated until a
DNS-pinned egress policy is installed in the runtime adapter.
"""
from __future__ import annotations

import ipaddress
from urllib.parse import urlsplit


DEFAULT_LOCAL_PROVIDER_PORTS = frozenset({1234, 8080, 11434})


_BLOCKED_CLOUD_SUFFIXES = (
    ".localhost",
    ".local",
    ".internal",
    ".test",
    ".example",
    ".invalid",
    ".lan",
    ".home.arpa",
    ".onion",
    ".corp",
    ".home",
    ".example.com",
    ".example.net",
    ".example.org",
)
_BLOCKED_CLOUD_HOSTS = {
    "localhost",
    "example.com",
    "example.net",
    "example.org",
    "metadata.google.internal",
    "metadata",
}


def validate_provider_endpoint(
    provider_type: str,
    base_url: str,
    *,
    local_allowed_ports: frozenset[int] = DEFAULT_LOCAL_PROVIDER_PORTS,
) -> None:
    """Validate endpoint class and URL syntax without making a network request.

    Local providers are restricted to loopback so an authenticated user cannot
    register an arbitrary private-network target. Cloud providers require HTTPS
    and a DNS hostname rather than an IP literal or reserved internal suffix.
    DNS resolution/pinning is still required before any user-managed endpoint
    is used for outbound traffic.
    """
    if provider_type not in {"cloud", "local"}:
        raise ValueError("Provider type is unsupported")
    if not isinstance(base_url, str) or not base_url or len(base_url) > 2048:
        raise ValueError("Provider endpoint URL is invalid")

    try:
        parsed = urlsplit(base_url)
        hostname = parsed.hostname
        port = parsed.port
    except ValueError:
        raise ValueError("Provider endpoint URL is invalid") from None

    if (
        parsed.scheme not in {"http", "https"}
        or not hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or "\\" in base_url
        or any(ord(character) < 32 or ord(character) == 127 for character in base_url)
    ):
        raise ValueError("Provider endpoint URL must be absolute and contain no credentials, query, or fragment")
    if port is not None and not 1 <= port <= 65535:
        raise ValueError("Provider endpoint port is invalid")

    normalized_host = hostname.rstrip(".").lower()
    try:
        address = ipaddress.ip_address(normalized_host)
    except ValueError:
        address = None

    if provider_type == "local":
        allowed_local_host = (
            address.is_loopback if address is not None
            else normalized_host in {"localhost", "host.docker.internal"}
        )
        if not allowed_local_host:
            raise ValueError("Local providers must use loopback or the explicit Docker host gateway")
        effective_port = port or (443 if parsed.scheme == "https" else 80)
        if effective_port not in local_allowed_ports:
            raise ValueError("Local provider port is not in the operator allowlist")
        return

    if parsed.scheme != "https":
        raise ValueError("Cloud providers must use HTTPS")
    if address is not None:
        raise ValueError("Cloud provider URLs must use a DNS hostname, not an IP literal")
    if (
        normalized_host in _BLOCKED_CLOUD_HOSTS
        or any(normalized_host.endswith(suffix) for suffix in _BLOCKED_CLOUD_SUFFIXES)
        or "." not in normalized_host
    ):
        raise ValueError("Cloud provider hostname is not allowed")
    try:
        ascii_host = normalized_host.encode("idna").decode("ascii")
    except UnicodeError:
        raise ValueError("Cloud provider hostname is invalid") from None
    if len(ascii_host) > 253:
        raise ValueError("Cloud provider hostname is invalid")
