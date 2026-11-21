import asyncio
import json

import httpx
import pytest

from galaxy_backend.lila_core.context import AssembledContext
from galaxy_backend.lila_core.contracts import CoreTurnResponse
from galaxy_backend.lila_core.memory import ConversationMessage, MemorySnippet
from galaxy_backend.lila_core.openai_compatible import (
    ModelProviderConfigurationError,
    ModelProviderConnectionUnavailable,
    ModelProviderUnavailable,
    OpenAICompatibleConfig,
    OpenAICompatibleModelAdapter,
)
from galaxy_backend.lila_core.service import ModelAdapterContractError


def make_context():
    return AssembledContext(
        conversation_id="conversation-42",
        messages=(
            ConversationMessage(role="assistant", content="Предыдущий ответ"),
            ConversationMessage(role="user", content="Продолжим?"),
        ),
        relevant_memories=(
            MemorySnippet(memory_id="private-memory-id", content="Пользователь предпочитает русский язык."),
        ),
    )


def make_config(**overrides):
    values = {
        "provider_id": "local-dev",
        "base_url": "http://127.0.0.1:1234/v1",
        "model_id": "qwen-local",
        "api_key": "provider-secret",
        "timeout_seconds": 7,
    }
    values.update(overrides)
    return OpenAICompatibleConfig(**values)


def test_adapter_sends_bounded_context_and_returns_non_secret_model_identity():
    captured = {}

    async def handler(request):
        captured["url"] = str(request.url)
        captured["authorization"] = request.headers.get("Authorization")
        captured["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "Я здесь, продолжим."}}]},
        )

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(client, make_config())
            return await adapter.generate(make_context())

    response = asyncio.run(scenario())

    assert isinstance(response, CoreTurnResponse)
    assert response.conversation_id == "conversation-42"
    assert response.text == "Я здесь, продолжим."
    assert response.provider_id == "local-dev"
    assert response.model_id == "qwen-local"
    assert captured["url"] == "http://127.0.0.1:1234/v1/chat/completions"
    assert captured["authorization"] == "Bearer provider-secret"
    messages = captured["body"]["messages"]
    assert messages[0]["role"] == "system"
    assert "untrusted" in messages[0]["content"].lower()
    assert messages[1]["role"] == "user"
    assert "Пользователь предпочитает русский язык" in messages[1]["content"]
    assert "private-memory-id" not in messages[1]["content"]
    assert messages[-2:] == [
        {"role": "assistant", "content": "Предыдущий ответ"},
        {"role": "user", "content": "Продолжим?"},
    ]
    assert captured["body"]["model"] == "qwen-local"
    assert captured["body"]["stream"] is False


def test_adapter_does_not_send_authorization_when_endpoint_has_no_api_key():
    captured = {}

    async def handler(request):
        captured["authorization"] = request.headers.get("Authorization")
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "Готово."}}]},
        )

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(
                client, make_config(api_key=None)
            )
            return await adapter.generate(make_context())

    response = asyncio.run(scenario())
    assert response.text == "Готово."
    assert captured["authorization"] is None


@pytest.mark.parametrize(
    ("status_code", "expected_reason"),
    [
        (400, "request_rejected"),
        (401, "authentication_rejected"),
        (403, "authentication_rejected"),
        (404, "endpoint_or_model_not_found"),
        (307, "redirect_refused"),
    ],
)
def test_provider_configuration_rejections_are_classified_and_sanitized(
    status_code, expected_reason
):
    async def handler(request):
        return httpx.Response(
            status_code,
            json={"error": {"message": "provider-secret private-provider-detail"}},
        )

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(client, make_config())
            with pytest.raises(ModelProviderConfigurationError) as captured:
                await adapter.generate(make_context())
            assert captured.value.reason_code == expected_reason
            assert captured.value.public_detail
            assert "provider-secret" not in str(captured.value)
            assert "private-provider-detail" not in str(captured.value)
            assert str(status_code) not in str(captured.value)

    asyncio.run(scenario())


@pytest.mark.parametrize("status_code", [408, 425, 429, 500, 502, 503])
def test_provider_transient_statuses_remain_retryable(status_code):
    async def handler(request):
        return httpx.Response(
            status_code,
            json={"error": {"message": "provider-secret private-provider-detail"}},
        )

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(client, make_config())
            with pytest.raises(ModelProviderUnavailable) as captured:
                await adapter.generate(make_context())
            assert "provider-secret" not in str(captured.value)
            assert "private-provider-detail" not in str(captured.value)
            assert str(status_code) not in str(captured.value)

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "failure",
    [
        httpx.ConnectError("provider-secret private-provider-detail"),
        httpx.ConnectTimeout("provider-secret private-provider-detail"),
    ],
)
def test_pre_send_connection_failures_are_definitive_and_sanitized(failure):
    async def handler(request):
        raise failure

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(client, make_config())
            with pytest.raises(ModelProviderConnectionUnavailable) as captured:
                await adapter.generate(make_context())
            assert "provider-secret" not in str(captured.value)
            assert "private-provider-detail" not in str(captured.value)

    asyncio.run(scenario())


def test_read_timeout_remains_ambiguous_and_keeps_provider_outcome_private():
    async def handler(request):
        raise httpx.ReadTimeout("provider-secret private-provider-detail")

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(client, make_config())
            with pytest.raises(ModelProviderUnavailable) as captured:
                await adapter.generate(make_context())
            assert "provider-secret" not in str(captured.value)
            assert "private-provider-detail" not in str(captured.value)

    asyncio.run(scenario())


def test_malformed_provider_response_is_rejected_without_raw_payload():
    async def handler(request):
        return httpx.Response(
            200,
            json={"error": {"message": "private-provider-detail"}},
        )

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(client, make_config())
            with pytest.raises(ModelAdapterContractError) as captured:
                await adapter.generate(make_context())
            assert "private-provider-detail" not in str(captured.value)

    asyncio.run(scenario())


def test_cleartext_endpoint_requires_loopback_or_literal_private_ip():
    make_config(base_url="http://localhost:1234/v1")
    make_config(base_url="http://192.168.1.25:1234/v1")
    make_config(base_url="https://provider.example/v1")

    with pytest.raises(ValueError, match="Cleartext"):
        make_config(base_url="http://provider.example/v1")
    with pytest.raises(ValueError, match="without credentials"):
        make_config(base_url="https://user:pass@provider.example/v1")
    with pytest.raises(ValueError, match="without credentials or query"):
        make_config(base_url="https://provider.example/v1?token=secret")


def test_provider_secret_is_excluded_from_config_and_adapter_repr():
    config = make_config()
    adapter = OpenAICompatibleModelAdapter(object(), config)

    assert "provider-secret" not in repr(config)
    assert "provider-secret" not in repr(adapter)

class TrackedChunkStream(httpx.AsyncByteStream):
    def __init__(self, chunks):
        self._chunks = chunks
        self.closed = False

    async def __aiter__(self):
        for chunk in self._chunks:
            yield chunk

    async def aclose(self):
        self.closed = True


def test_oversized_chunked_provider_response_is_bounded_and_closed():
    stream = TrackedChunkStream(
        [b"x" * (64 * 1024) for _ in range(17)]
    )

    async def handler(request):
        return httpx.Response(200, stream=stream)

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(client, make_config())
            with pytest.raises(ModelAdapterContractError, match="response exceeds the supported size"):
                await adapter.generate(make_context())

    asyncio.run(scenario())
    assert stream.closed is True


def test_chunked_provider_response_is_decoded_across_chunk_boundaries():
    raw = json.dumps(
        {"choices": [{"message": {"content": "Ответ из нескольких частей"}}]},
        ensure_ascii=False,
    ).encode("utf-8")
    stream = TrackedChunkStream([raw[:7], raw[7:19], raw[19:]])

    async def handler(request):
        return httpx.Response(200, stream=stream)

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(client, make_config())
            return await adapter.generate(make_context())

    response = asyncio.run(scenario())
    assert response.text == "Ответ из нескольких частей"
    assert stream.closed is True



@pytest.mark.parametrize(
    ("runtime", "base_url", "expected_url"),
    [
        (
            "LM Studio",
            "http://127.0.0.1:1234/v1",
            "http://127.0.0.1:1234/v1/models",
        ),
        (
            "Ollama OpenAI-compatible API",
            "http://127.0.0.1:11434/v1",
            "http://127.0.0.1:11434/v1/models",
        ),
        (
            "llama.cpp server OpenAI-compatible API",
            "http://127.0.0.1:8080/v1",
            "http://127.0.0.1:8080/v1/models",
        ),
    ],
)
def test_model_discovery_uses_common_local_openai_compatible_contract(
    runtime, base_url, expected_url
):
    captured = {}

    async def handler(request):
        captured["url"] = str(request.url)
        captured["authorization"] = request.headers.get("Authorization")
        return httpx.Response(
            200,
            json={
                "object": "list",
                "data": [
                    {"id": "qwen-local"},
                    {"id": "llama-local"},
                    {"id": "qwen-local"},
                    {"id": ""},
                    {"id": "invalid\nmodel"},
                ],
            },
        )

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(
                client,
                make_config(base_url=base_url, api_key=None),
            )
            return await adapter.discover_models()

    assert asyncio.run(scenario()) == ("qwen-local", "llama-local")
    assert captured["url"] == expected_url
    assert captured["authorization"] is None


@pytest.mark.parametrize("status_code", [404, 405])
def test_model_discovery_reports_unsupported_route_without_generation(status_code):
    captured = []

    async def handler(request):
        captured.append((request.method, str(request.url)))
        return httpx.Response(status_code)

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(client, make_config())
            return await adapter.discover_models()

    assert asyncio.run(scenario()) is None
    assert captured == [("GET", "http://127.0.0.1:1234/v1/models")]


def test_model_discovery_rejects_malformed_catalog_without_raw_payload():
    async def handler(request):
        return httpx.Response(
            200,
            json={"error": {"message": "provider-secret-private-detail"}},
        )

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(client, make_config())
            with pytest.raises(ModelAdapterContractError) as captured:
                await adapter.discover_models()
            assert "provider-secret-private-detail" not in str(captured.value)

    asyncio.run(scenario())


def test_model_discovery_rejects_oversized_catalog_and_closes_stream():
    stream = TrackedChunkStream([b"x" * (16 * 1024) for _ in range(5)])

    async def handler(request):
        return httpx.Response(200, stream=stream)

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(client, make_config())
            with pytest.raises(ModelAdapterContractError, match="catalog exceeds"):
                await adapter.discover_models()

    asyncio.run(scenario())
    assert stream.closed is True


def test_model_discovery_maps_auth_rejection_without_provider_details():
    async def handler(request):
        return httpx.Response(
            401,
            json={"error": {"message": "provider-secret-private-detail"}},
        )

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(client, make_config())
            with pytest.raises(ModelProviderConfigurationError) as captured:
                await adapter.discover_models()
            assert captured.value.public_detail
            assert "provider-secret" not in str(captured.value)
            assert "private-detail" not in str(captured.value)

    asyncio.run(scenario())
