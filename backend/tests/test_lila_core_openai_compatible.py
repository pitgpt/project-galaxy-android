import asyncio
import json

import httpx
import pytest

from galaxy_backend.lila_core.context import AssembledContext
from galaxy_backend.lila_core.contracts import CoreTurnResponse
from galaxy_backend.lila_core.memory import ConversationMessage, MemorySnippet
from galaxy_backend.lila_core.openai_compatible import (
    OpenAICompatibleConfig,
    OpenAICompatibleModelAdapter,
    ModelProviderUnavailable,
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


def test_provider_http_error_is_sanitized():
    async def handler(request):
        return httpx.Response(
            401,
            json={"error": {"message": "provider-secret must not escape"}},
        )

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OpenAICompatibleModelAdapter(client, make_config())
            with pytest.raises(ModelProviderUnavailable) as captured:
                await adapter.generate(make_context())
            assert "provider-secret" not in str(captured.value)
            assert "401" not in str(captured.value)

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
