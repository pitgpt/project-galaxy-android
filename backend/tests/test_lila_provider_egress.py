import asyncio

import httpcore
import pytest

from galaxy_backend.lila_core.provider_egress import (
    PinnedAsyncNetworkBackend,
    create_provider_http_client,
)


class RecordingBackend:
    def __init__(self):
        self.calls = []

    async def connect_tcp(self, host, port, timeout=None, local_address=None, socket_options=None):
        self.calls.append((host, port, timeout, local_address, socket_options))
        return object()

    async def connect_unix_socket(self, path, timeout=None, socket_options=None):
        raise AssertionError("Unix sockets must not be used")

    async def sleep(self, seconds):
        await asyncio.sleep(seconds)


@pytest.mark.asyncio
async def test_cloud_dns_is_resolved_and_socket_is_pinned_to_public_ip():
    backend = RecordingBackend()
    resolver_calls = []

    async def resolver(host, port):
        resolver_calls.append((host, port))
        return ["8.8.8.8", "1.1.1.1"]

    network = PinnedAsyncNetworkBackend("cloud", resolver=resolver, backend=backend)
    stream = await network.connect_tcp("api.vendor.com", 443, timeout=2.0)

    assert stream is not None
    assert resolver_calls == [("api.vendor.com.", 443)]
    assert backend.calls[0][0] == "8.8.8.8"
    assert backend.calls[0][1] == 443


@pytest.mark.asyncio
async def test_cloud_rejects_mixed_public_and_private_dns_answers_before_connect():
    backend = RecordingBackend()

    async def resolver(host, port):
        return ["8.8.8.8", "10.0.0.10"]

    network = PinnedAsyncNetworkBackend("cloud", resolver=resolver, backend=backend)
    with pytest.raises(httpcore.ConnectError):
        await network.connect_tcp("api.vendor.com", 443, timeout=2.0)

    assert backend.calls == []


@pytest.mark.asyncio
async def test_cloud_rejects_ip_literal_without_dns_or_socket_attempt():
    backend = RecordingBackend()
    resolver_calls = []

    async def resolver(host, port):
        resolver_calls.append((host, port))
        return ["8.8.8.8"]

    network = PinnedAsyncNetworkBackend("cloud", resolver=resolver, backend=backend)
    with pytest.raises(httpcore.ConnectError):
        await network.connect_tcp("8.8.8.8", 443, timeout=2.0)

    assert resolver_calls == []
    assert backend.calls == []


@pytest.mark.asyncio
async def test_localhost_accepts_only_loopback_dns_answers():
    backend = RecordingBackend()

    async def resolver(host, port):
        assert host == "localhost"
        return ["::1", "127.0.0.1"]

    network = PinnedAsyncNetworkBackend("local", resolver=resolver, backend=backend)
    await network.connect_tcp("localhost", 1234, timeout=2.0)

    assert backend.calls[0][0] == "::1"


@pytest.mark.asyncio
async def test_localhost_rejects_non_loopback_dns_answer():
    backend = RecordingBackend()

    async def resolver(host, port):
        return ["127.0.0.1", "192.168.1.10"]

    network = PinnedAsyncNetworkBackend("local", resolver=resolver, backend=backend)
    with pytest.raises(httpcore.ConnectError):
        await network.connect_tcp("localhost", 1234, timeout=2.0)

    assert backend.calls == []


def test_local_provider_rejects_unapproved_port_before_dns():
    backend = RecordingBackend()
    resolver_calls = []

    async def resolver(host, port):
        resolver_calls.append((host, port))
        return ["127.0.0.1"]

    network = PinnedAsyncNetworkBackend("local", resolver=resolver, backend=backend)
    with pytest.raises(httpcore.ConnectError):
        await network.connect_tcp("localhost", 6379, timeout=2.0)
    assert resolver_calls == []
    assert backend.calls == []


@pytest.mark.asyncio
async def test_local_docker_host_gateway_is_pinned_to_one_private_address():
    backend = RecordingBackend()
    resolver_calls = []

    async def resolver(host, port):
        resolver_calls.append((host, port))
        return ["172.17.0.1"]

    network = PinnedAsyncNetworkBackend(
        "local", resolver=resolver, backend=backend, local_allowed_ports={1234}
    )
    await network.connect_tcp("host.docker.internal", 1234, timeout=2.0)
    assert resolver_calls == [("host.docker.internal", 1234)]
    assert backend.calls[0][0] == "172.17.0.1"
    assert backend.calls[0][1] == 1234


@pytest.mark.asyncio
async def test_local_docker_host_gateway_rejects_multiple_or_public_addresses():
    backend = RecordingBackend()

    async def mixed_resolver(host, port):
        return ["172.17.0.1", "8.8.8.8"]

    network = PinnedAsyncNetworkBackend(
        "local", resolver=mixed_resolver, backend=backend, local_allowed_ports={1234}
    )
    with pytest.raises(httpcore.ConnectError):
        await network.connect_tcp("host.docker.internal", 1234, timeout=2.0)
    assert backend.calls == []


@pytest.mark.asyncio
async def test_local_provider_rejects_unapproved_hostname():
    backend = RecordingBackend()
    network = PinnedAsyncNetworkBackend("local", resolver=lambda host, port: None, backend=backend)
    with pytest.raises(httpcore.ConnectError):
        await network.connect_tcp("arbitrary.internal", 1234, timeout=2.0)
    assert backend.calls == []


@pytest.mark.asyncio
async def test_local_loopback_http_request_uses_pinned_transport():
    async def handle(reader, writer):
        await reader.readline()
        while True:
            line = await reader.readline()
            if line in (b"\r\n", b"\n", b""):
                break
        writer.write(
            b"HTTP/1.1 200 OK\r\n"
            b"Content-Length: 2\r\n"
            b"Connection: close\r\n\r\n{}"
        )
        await writer.drain()
        writer.close()
        await writer.wait_closed()

    server = await asyncio.start_server(handle, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    try:
        async with create_provider_http_client("local", 2.0, local_allowed_ports={port}) as client:
            response = await client.get(f"http://127.0.0.1:{port}/models")
        assert response.status_code == 200
        assert response.text == "{}"
    finally:
        server.close()
        await server.wait_closed()


@pytest.mark.asyncio
async def test_local_dns_timeout_fails_as_connect_timeout():
    backend = RecordingBackend()

    async def resolver(host, port):
        await asyncio.sleep(0.05)
        return ["127.0.0.1"]

    network = PinnedAsyncNetworkBackend("local", resolver=resolver, backend=backend)
    with pytest.raises(httpcore.ConnectTimeout):
        await network.connect_tcp("localhost", 1234, timeout=0.001)

    assert backend.calls == []


@pytest.mark.asyncio
async def test_localhost_name_connects_to_ipv4_loopback_when_ipv6_is_unavailable():
    async def handle(reader, writer):
        await reader.readline()
        while True:
            line = await reader.readline()
            if line in (b"\r\n", b"\n", b""):
                break
        writer.write(
            b"HTTP/1.1 200 OK\r\n"
            b"Content-Length: 2\r\n"
            b"Connection: close\r\n\r\n{}"
        )
        await writer.drain()
        writer.close()
        await writer.wait_closed()

    server = await asyncio.start_server(handle, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    try:
        async with create_provider_http_client("local", 2.0, local_allowed_ports={port}) as client:
            response = await client.get(f"http://localhost:{port}/models")
        assert response.status_code == 200
        assert response.text == "{}"
    finally:
        server.close()
        await server.wait_closed()
