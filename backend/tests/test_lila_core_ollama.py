import asyncio
import json

import httpx
import pytest

from galaxy_backend.lila_core.adapters import (
    ModelAdapterContractError,
    ModelProviderConnectionUnavailable,
)
from galaxy_backend.lila_core.context import AssembledContext
from galaxy_backend.lila_core.memory import ConversationMessage, MemorySnippet
from galaxy_backend.lila_core.ollama import OllamaModelAdapter
from galaxy_backend.lila_core.openai_compatible import (
    ModelProviderConfigurationError,
    ModelProviderUnavailable,
    OpenAICompatibleConfig,
)
from galaxy_backend.lila_core.openai_compatible import OpenAICompatibleModelAdapter
from galaxy_backend.lila_core.provider_routing import create_provider_model_adapter
from galaxy_backend.lila_core.provider_registry import (
    DEFAULT_LOCAL_PROVIDER_PORTS,
    validate_provider_endpoint,
)


def make_config(**overrides):
    values = {
        "provider_id": "ollama-local",
        "base_url": "http://127.0.0.1:11434",
        "model_id": "qwen3:8b",
        "api_key": None,
        "timeout_seconds": 7,
    }
    values.update(overrides)
    return OpenAICompatibleConfig(**values)


def make_context():
    return AssembledContext(
        conversation_id="conversation-ollama",
        messages=(
            ConversationMessage(role="assistant", content="Предыдущий ответ"),
            ConversationMessage(role="user", content="Продолжим?"),
        ),
        relevant_memories=(
            MemorySnippet(
                memory_id="private-memory-id",
                content="Пользователь предпочитает русский язык",
            ),
        ),
    )


def test_native_ollama_generation_uses_native_chat_shape_and_lila_context():
    captured = {}

    async def handler(request):
        captured["url"] = str(request.url)
        captured["authorization"] = request.headers.get("Authorization")
        captured["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "qwen3:8b",
                "message": {"role": "assistant", "content": "Я здесь, продолжим."},
                "done": True,
            },
        )

    async def scenario():
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler),
            follow_redirects=False,
            trust_env=False,
        ) as client:
            adapter = OllamaModelAdapter(client, make_config())
            return await adapter.generate(make_context())

    response = asyncio.run(scenario())

    assert response.conversation_id == "conversation-ollama"
    assert response.text == "Я здесь, продолжим."
    assert response.provider_id == "ollama-local"
    assert response.model_id == "qwen3:8b"
    assert captured["url"] == "http://127.0.0.1:11434/api/chat"
    assert captured["authorization"] is None
    assert captured["body"]["model"] == "qwen3:8b"
    assert captured["body"]["stream"] is False
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


def test_native_ollama_discovery_reads_tags_without_generation():
    captured = {}

    async def handler(request):
        captured["method"] = request.method
        captured["path"] = request.url.path
        return httpx.Response(
            200,
            json={
                "models": [
                    {"name": "qwen3:8b"},
                    {"model": "llama3.2:latest"},
                    {"name": "qwen3:8b"},
                ],
            },
        )

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OllamaModelAdapter(client, make_config())
            return await adapter.discover_models()

    assert asyncio.run(scenario()) == ("qwen3:8b", "llama3.2:latest")
    assert captured == {"method": "GET", "path": "/api/tags"}


def test_native_ollama_discovery_reports_unsupported_catalog_without_generation():
    async def handler(request):
        assert request.method == "GET"
        assert request.url.path == "/api/tags"
        return httpx.Response(404, json={"error": "not found"})

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            return await OllamaModelAdapter(client, make_config()).discover_models()

    assert asyncio.run(scenario()) is None


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
def test_native_ollama_configuration_failures_are_sanitized(status_code, expected_reason):
    secret = "ollama-private-secret"

    async def handler(request):
        return httpx.Response(
            status_code,
            text=f"provider diagnostic containing {secret}",
        )

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OllamaModelAdapter(client, make_config(api_key=secret))
            with pytest.raises(ModelProviderConfigurationError) as captured:
                await adapter.generate(make_context())
            assert captured.value.reason_code == expected_reason
            assert secret not in str(captured.value)
            assert "provider diagnostic" not in str(captured.value)

    asyncio.run(scenario())


@pytest.mark.parametrize("status_code", [408, 425, 429, 500, 502, 503])
def test_native_ollama_transient_statuses_remain_ambiguous_and_sanitized(status_code):
    secret = "ollama-private-secret"

    async def handler(request):
        return httpx.Response(status_code, text=f"provider diagnostic {secret}")

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OllamaModelAdapter(client, make_config(api_key=secret))
            with pytest.raises(ModelProviderUnavailable) as captured:
                await adapter.generate(make_context())
            assert secret not in str(captured.value)
            assert "provider diagnostic" not in str(captured.value)

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "failure",
    [
        httpx.ConnectError("private provider detail"),
        httpx.ConnectTimeout("private provider detail"),
    ],
)
def test_native_ollama_pre_send_connect_failure_is_retry_safe(failure):
    async def handler(request):
        raise failure

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OllamaModelAdapter(client, make_config())
            with pytest.raises(ModelProviderConnectionUnavailable):
                await adapter.generate(make_context())

    asyncio.run(scenario())


def test_native_ollama_read_timeout_is_ambiguous():
    async def handler(request):
        raise httpx.ReadTimeout("private provider detail")

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OllamaModelAdapter(client, make_config())
            with pytest.raises(ModelProviderUnavailable):
                await adapter.generate(make_context())

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "payload",
    [
        {"message": {"role": "assistant", "content": "partial"}, "done": False},
        {"message": {"role": "assistant", "content": ""}, "done": True},
        {"message": {"role": "assistant", "content": "ok"}},
        {"error": "private provider diagnostic"},
    ],
)
def test_native_ollama_rejects_incomplete_or_malformed_response(payload):
    async def handler(request):
        return httpx.Response(200, json=payload)

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            adapter = OllamaModelAdapter(client, make_config())
            with pytest.raises(ModelAdapterContractError):
                await adapter.generate(make_context())

    asyncio.run(scenario())


def test_native_ollama_adapter_repr_never_exposes_api_key():
    secret = "ollama-private-secret"
    config = make_config(api_key=secret)
    adapter = OllamaModelAdapter(object(), config)
    assert secret not in repr(config)
    assert secret not in repr(adapter)


def test_provider_adapter_selection_distinguishes_native_and_openai_compatible_ollama():
    native = create_provider_model_adapter(object(), "local", make_config())
    compatible = create_provider_model_adapter(
        object(),
        "local",
        make_config(base_url="http://127.0.0.1:11434/v1"),
    )
    cloud = create_provider_model_adapter(
        object(),
        "cloud",
        make_config(base_url="https://api.vendor.example/v1"),
    )

    assert isinstance(native, OllamaModelAdapter)
    assert isinstance(compatible, OpenAICompatibleModelAdapter)
    assert isinstance(cloud, OpenAICompatibleModelAdapter)



@pytest.mark.parametrize(
    ("runtime", "provider_id", "base_url", "port"),
    [
        (
            "LM Studio",
            "lm-studio",
            "http://127.0.0.1:1234/v1",
            1234,
        ),
        (
            "llama.cpp server",
            "llama-cpp",
            "http://127.0.0.1:8080/v1",
            8080,
        ),
    ],
)
def test_openai_compatible_local_runtime_profiles_support_discovery_and_chat(
    runtime, provider_id, base_url, port
):
    assert port in DEFAULT_LOCAL_PROVIDER_PORTS
    validate_provider_endpoint("local", base_url)
    captured = []

    async def handler(request):
        captured.append(
            {
                "method": request.method,
                "path": request.url.path,
                "authorization": request.headers.get("Authorization"),
                "body": json.loads(request.content) if request.content else None,
            }
        )
        if request.method == "GET" and request.url.path == "/v1/models":
            return httpx.Response(200, json={"data": [{"id": "qwen-local"}]})
        if request.method == "POST" and request.url.path == "/v1/chat/completions":
            return httpx.Response(
                200,
                json={
                    "choices": [
                        {"message": {"role": "assistant", "content": f"{runtime} response"}}
                    ]
                },
            )
        return httpx.Response(404, json={"error": "not found"})

    async def scenario():
        config = make_config(
            provider_id=provider_id,
            base_url=base_url,
            model_id="qwen-local",
            api_key=None,
        )
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler),
            follow_redirects=False,
            trust_env=False,
        ) as client:
            adapter = create_provider_model_adapter(client, "local", config)
            assert isinstance(adapter, OpenAICompatibleModelAdapter)
            assert not isinstance(adapter, OllamaModelAdapter)
            models = await adapter.discover_models()
            response = await adapter.generate(make_context())
            return models, response

    models, response = asyncio.run(scenario())

    assert models == ("qwen-local",)
    assert response.text == f"{runtime} response"
    assert response.provider_id == provider_id
    assert response.model_id == "qwen-local"
    assert [(item["method"], item["path"]) for item in captured] == [
        ("GET", "/v1/models"),
        ("POST", "/v1/chat/completions"),
    ]
    assert all(item["authorization"] is None for item in captured)
    assert captured[1]["body"]["model"] == "qwen-local"
    assert captured[1]["body"]["stream"] is False
