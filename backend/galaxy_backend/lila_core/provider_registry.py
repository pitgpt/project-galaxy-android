"""Validation helpers for owner-managed Lila provider records.

This module validates configuration at rest. It deliberately does not perform
outbound requests: user-managed endpoints must not be activated until a
DNS-pinned egress policy is installed in the runtime adapter.
"""
from __future__ import annotations

import ipaddress
from urllib.parse import urlsplit


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
)
_BLOCKED_CLOUD_HOSTS = {
    "localhost",
    "metadata.google.internal",
    "metadata",
}


def validate_provider_endpoint(provider_type: str, base_url: str) -> None:
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
        is_loopback = (
            address.is_loopback if address is not None
            else normalized_host == "localhost"
        )
        if not is_loopback:
            raise ValueError("Local providers must use localhost or a literal loopback IP")
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
