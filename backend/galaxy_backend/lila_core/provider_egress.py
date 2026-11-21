"""DNS-pinned egress transport for owner-managed Lila providers.

The transport resolves each new connection itself, validates every DNS answer,
and connects to the selected IP literal. HTTPCore still performs TLS using the
original request hostname, preserving SNI and certificate hostname validation.
No proxy or redirect is used for user-managed provider traffic.
"""
from __future__ import annotations

import asyncio
import ipaddress
import socket
import time
from collections.abc import Awaitable, Callable, Iterable

import httpcore
import httpx
from httpcore._backends.auto import AutoBackend


AddressResolver = Callable[[str, int], Awaitable[Iterable[str]]]


class PinnedAsyncNetworkBackend:
    """Resolve once per TCP connection and pin the socket to an approved IP."""

    def __init__(
        self,
        provider_type: str,
        *,
        resolver: AddressResolver | None = None,
        backend=None,
    ) -> None:
        if provider_type not in {"cloud", "local"}:
            raise ValueError("Unsupported provider type")
        self._provider_type = provider_type
        self._resolver = resolver or self._resolve_system
        self._backend = backend or AutoBackend()

    @staticmethod
    async def _resolve_system(host: str, port: int) -> tuple[str, ...]:
        loop = asyncio.get_running_loop()
        records = await loop.getaddrinfo(
            host,
            port,
            family=socket.AF_UNSPEC,
            type=socket.SOCK_STREAM,
            proto=socket.IPPROTO_TCP,
        )
        return tuple(record[4][0] for record in records)

    def _approved(self, address: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
        if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped is not None:
            address = address.ipv4_mapped
        if self._provider_type == "local":
            return address.is_loopback
        return address.is_global

    async def connect_tcp(
        self,
        host: str,
        port: int,
        timeout: float | None = None,
        local_address: str | None = None,
        socket_options=None,
    ):
        started = time.monotonic()
        normalized_host = host.rstrip(".").lower()
        if not normalized_host or "%" in normalized_host:
            raise httpcore.ConnectError("Provider host is invalid")

        try:
            literal = ipaddress.ip_address(normalized_host)
        except ValueError:
            literal = None

        if self._provider_type == "cloud" and literal is not None:
            raise httpcore.ConnectError("Cloud provider DNS host is required")
        if self._provider_type == "local" and literal is None and normalized_host != "localhost":
            raise httpcore.ConnectError("Local provider must use loopback")

        if literal is not None:
            addresses = (literal,)
        else:
            try:
                ascii_host = normalized_host.encode("idna").decode("ascii")
                # "localhost." is not present in every NSS hosts database;
                # the literal localhost name is safe because local providers
                # are restricted to loopback and every answer is checked.
                query_host = ascii_host if normalized_host == "localhost" else ascii_host + "."
                resolve_task = self._resolver(query_host, port)
                if timeout is None:
                    raw_addresses = await resolve_task
                else:
                    remaining = timeout - (time.monotonic() - started)
                    if remaining <= 0:
                        raise asyncio.TimeoutError
                    raw_addresses = await asyncio.wait_for(resolve_task, timeout=remaining)
                parsed_addresses = []
                seen = set()
                for raw in raw_addresses:
                    if not isinstance(raw, str) or "%" in raw:
                        raise ValueError("Invalid DNS address")
                    address = ipaddress.ip_address(raw)
                    if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped is not None:
                        address = address.ipv4_mapped
                    if address not in seen:
                        seen.add(address)
                        parsed_addresses.append(address)
                addresses = tuple(parsed_addresses)
            except asyncio.TimeoutError:
                raise httpcore.ConnectTimeout("Provider DNS resolution timed out") from None
            except (OSError, UnicodeError, ValueError, TypeError):
                raise httpcore.ConnectError("Provider DNS resolution failed") from None

        if not addresses:
            raise httpcore.ConnectError("Provider DNS resolution returned no addresses")
        if any(not self._approved(address) for address in addresses):
            raise httpcore.ConnectError("Provider DNS resolution returned a disallowed address")

        last_error = None
        for address in addresses:
            remaining = None
            if timeout is not None:
                remaining = timeout - (time.monotonic() - started)
                if remaining <= 0:
                    raise httpcore.ConnectTimeout("Provider connection timed out") from None
            try:
                return await self._backend.connect_tcp(
                    str(address),
                    port,
                    timeout=remaining,
                    local_address=local_address,
                    socket_options=socket_options,
                )
            except (httpcore.ConnectError, httpcore.ConnectTimeout) as exc:
                last_error = exc

        if isinstance(last_error, httpcore.ConnectTimeout):
            raise httpcore.ConnectTimeout("Provider connection timed out") from None
        raise httpcore.ConnectError("Provider connection failed") from None

    async def connect_unix_socket(self, path: str, timeout: float | None = None, socket_options=None):
        raise httpcore.UnsupportedProtocol("Unix-domain provider sockets are not supported")

    async def sleep(self, seconds: float) -> None:
        await self._backend.sleep(seconds)


class PinnedAsyncHTTPTransport(httpx.AsyncHTTPTransport):
    """HTTPX transport whose TCP connections use the DNS-pinned backend."""

    def __init__(self, provider_type: str) -> None:
        ssl_context = httpx.create_ssl_context(verify=True, trust_env=False)
        self._pool = httpcore.AsyncConnectionPool(
            ssl_context=ssl_context,
            max_connections=10,
            max_keepalive_connections=2,
            keepalive_expiry=5.0,
            http1=True,
            http2=False,
            retries=0,
            network_backend=PinnedAsyncNetworkBackend(provider_type),
        )


def create_provider_http_client(
    provider_type: str,
    timeout_seconds: float = 30.0,
) -> httpx.AsyncClient:
    """Create a no-proxy, no-redirect client with DNS-pinned egress."""
    if not 1.0 <= float(timeout_seconds) <= 120.0:
        raise ValueError("Provider timeout must be between 1 and 120 seconds")
    transport = PinnedAsyncHTTPTransport(provider_type)
    return httpx.AsyncClient(
        transport=transport,
        timeout=httpx.Timeout(
            float(timeout_seconds),
            connect=min(5.0, float(timeout_seconds)),
        ),
        follow_redirects=False,
        trust_env=False,
    )
