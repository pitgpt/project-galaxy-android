import asyncio
import sqlite3
from uuid import uuid4

import httpx
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from galaxy_backend.config import Settings
from galaxy_backend.lila_core.image_generation import (
    ComfyUiFailure,
    ComfyUiImageAdapter,
    ComfyUiProviderConfigRequest,
    ComfyUiWorkflowConfig,
    ImageGenerationRequest,
    ImageJobNotCancellable,
    LilaImageGenerationService,
)
from galaxy_backend.lila_core.image_store import (
    ImageIdempotencyConflict,
    ImageJobNotFound,
    SqliteLilaImageStore,
)
from galaxy_backend.lila_core.sqlite_store import SqliteLilaStore
from galaxy_backend.main import create_app


KEY = bytes.fromhex("3031323334353637383961626364656630313233343536373839616263646566")


def run(coro):
    return asyncio.run(coro)


def png_bytes(width=64, height=64):
    return (
        b"\x89PNG\r\n\x1a\n"
        + (13).to_bytes(4, "big")
        + b"IHDR"
        + width.to_bytes(4, "big")
        + height.to_bytes(4, "big")
        + b"\x08\x06\x00\x00\x00"
        + b"\x00\x00\x00\x00"
    )


def workflow_config():
    return ComfyUiWorkflowConfig(
        workflow_id="basic-v1",
        workflow_json=(
            '{"3":{"inputs":{"text":""}},'
            '"4":{"inputs":{"width":512,"height":512,"seed":1}}}'
        ),
        positive_prompt={"node_id": "3", "input_name": "text"},
        width={"node_id": "4", "input_name": "width"},
        height={"node_id": "4", "input_name": "height"},
        seed={"node_id": "4", "input_name": "seed"},
    )


def provider_payload(token="comfy-secret"):
    return ComfyUiProviderConfigRequest(
        base_url="http://127.0.0.1:8188",
        bearer_token=token,
        workflow=workflow_config(),
    )


def request_payload(conversation_id="conversation-a", prompt="A quiet observatory"):
    return ImageGenerationRequest(
        conversation_id=conversation_id,
        prompt=prompt,
        negative_prompt="blurry",
        width=64,
        height=64,
        seed=7,
    )


def make_handler(mode):
    state = {"mode": mode, "prompt_id": None, "post_prompt": 0, "post_queue": 0, "requests": []}
    image = png_bytes()

    async def handler(request: httpx.Request):
        state["requests"].append((request.method, request.url.path))
        if request.url.path == "/system_stats":
            return httpx.Response(200, json={"system": {}, "devices": []})
        if request.url.path == "/prompt" and request.method == "POST":
            state["post_prompt"] += 1
            body = __import__("json").loads(request.content)
            state["prompt_id"] = body["prompt_id"]
            if state["mode"] == "submit-unknown":
                return httpx.Response(500, json={"error": "private provider detail"})
            return httpx.Response(200, json={"prompt_id": body["prompt_id"], "number": 1})
        if request.url.path == "/queue" and request.method == "GET":
            prompt_id = state["prompt_id"] or "not-submitted"
            if state["mode"] == "queued":
                return httpx.Response(200, json={
                    "queue_running": [],
                    "queue_pending": [[1, prompt_id, {}, {}]],
                })
            if state["mode"] == "running":
                return httpx.Response(200, json={
                    "queue_running": [[1, prompt_id, {}, {}]],
                    "queue_pending": [],
                })
            return httpx.Response(200, json={"queue_running": [], "queue_pending": []})
        if request.url.path.startswith("/history/"):
            if state["mode"] == "completed":
                prompt_id = request.url.path.rsplit("/", 1)[-1]
                return httpx.Response(200, json={
                    prompt_id: {
                        "status": {"status_str": "success", "completed": True},
                        "outputs": {
                            "9": {"images": [{"filename": "result.png", "subfolder": "", "type": "output"}]}
                        },
                    }
                })
            return httpx.Response(404, json={})
        if request.url.path == "/queue" and request.method == "POST":
            state["post_queue"] += 1
            state["mode"] = "cancelled"
            return httpx.Response(200, json={"deleted": [state["prompt_id"]]})
        if request.url.path == "/view":
            return httpx.Response(200, content=image, headers={"content-type": "image/png"})
        return httpx.Response(404, json={})

    return state, handler


def make_service(tmp_path, mode="queued"):
    state, handler = make_handler(mode)

    def client_factory(*, timeout_seconds, local_allowed_ports):
        assert set(local_allowed_ports) == {8188}
        return httpx.AsyncClient(
            transport=httpx.MockTransport(handler),
            timeout=timeout_seconds,
            follow_redirects=False,
        )

    store = SqliteLilaImageStore(tmp_path / "lila.sqlite3", KEY)
    service = LilaImageGenerationService(store, client_factory=client_factory)
    return service, store, state


def test_workflow_mapping_validation_and_prompt_building():
    workflow = workflow_config()
    request = request_payload()
    graph = workflow.build_prompt(request)
    assert graph["3"]["inputs"]["text"] == "A quiet observatory"
    assert graph["4"]["inputs"]["width"] == 64
    assert graph["4"]["inputs"]["height"] == 64
    assert graph["4"]["inputs"]["seed"] == 7

    with pytest.raises(ValueError, match="mapped node input"):
        ComfyUiWorkflowConfig(
            workflow_id="bad",
            workflow_json='{"3":{"inputs":{"text":""}}}',
            positive_prompt={"node_id": "3", "input_name": "missing"},
        )


def test_image_store_is_encrypted_owner_scoped_and_survives_recreation(tmp_path):
    path = tmp_path / "lila.sqlite3"
    store = SqliteLilaImageStore(path, KEY)
    config = {
        "base_url": "http://127.0.0.1:8188",
        "bearer_token": "do-not-store-in-plaintext",
        "timeout_seconds": 30.0,
        "workflow": workflow_config().model_dump(mode="json"),
    }
    run(store.save_provider_config(principal_id="owner-a", config=config))
    assert run(store.get_provider_config(principal_id="owner-a")) == config
    assert run(store.get_provider_config(principal_id="owner-b")) is None

    request = request_payload()
    job, created = run(store.create_job(
        principal_id="owner-a",
        conversation_id=request.conversation_id,
        idempotency_key=str(uuid4()),
        request=request.model_dump(mode="json", exclude={"conversation_id"}),
        provider_config=config,
    ))
    assert created is True
    assert job["state"] == "prepared"
    assert run(store.claim_submission(principal_id="owner-a", job_id=job["job_id"])) is True
    assert run(store.claim_submission(principal_id="owner-a", job_id=job["job_id"])) is False
    run(store.update_job(principal_id="owner-a", job_id=job["job_id"], state="queued"))
    run(store.update_job(principal_id="owner-a", job_id=job["job_id"], state="provider_completed"))
    image = png_bytes()
    run(store.save_asset(
        principal_id="owner-a", job_id=job["job_id"], mime_type="image/png",
        width=64, height=64, image_bytes=image,
    ))

    assert run(store.get_job(principal_id="owner-b", job_id=job["job_id"])) is None
    assert run(store.get_asset(principal_id="owner-b", job_id=job["job_id"])) is None

    raw = b"".join(
        candidate.read_bytes()
        for candidate in (path, path.with_name(path.name + "-wal"), path.with_name(path.name + "-shm"))
        if candidate.exists()
    )
    for secret in (
        b"owner-a",
        b"conversation-a",
        b"A quiet observatory",
        b"do-not-store-in-plaintext",
        b"result.png",
        image,
    ):
        assert secret not in raw

    reopened = SqliteLilaImageStore(path, KEY)
    restored = run(reopened.get_job(principal_id="owner-a", job_id=job["job_id"]))
    asset = run(reopened.get_asset(principal_id="owner-a", job_id=job["job_id"]))
    assert restored["state"] == "completed"
    assert restored["asset"]["sha256"] == asset["sha256"]
    assert asset["bytes"] == image


def test_image_store_idempotency_conflict_and_state_machine(tmp_path):
    store = SqliteLilaImageStore(tmp_path / "lila.sqlite3", KEY)
    request = request_payload()
    key = str(uuid4())
    first, created = run(store.create_job(
        principal_id="owner",
        conversation_id=request.conversation_id,
        idempotency_key=key,
        request=request.model_dump(mode="json", exclude={"conversation_id"}),
        provider_config={"base_url": "http://127.0.0.1:8188"},
    ))
    duplicate, created_again = run(store.create_job(
        principal_id="owner",
        conversation_id=request.conversation_id,
        idempotency_key=key,
        request=request.model_dump(mode="json", exclude={"conversation_id"}),
        provider_config={"base_url": "http://127.0.0.1:8188"},
    ))
    assert created is True
    assert created_again is False
    assert duplicate["job_id"] == first["job_id"]

    with pytest.raises(ImageIdempotencyConflict):
        run(store.create_job(
            principal_id="owner",
            conversation_id=request.conversation_id,
            idempotency_key=key,
            request={**request.model_dump(mode="json", exclude={"conversation_id"}), "prompt": "different"},
            provider_config={"base_url": "http://127.0.0.1:8188"},
        ))
    with pytest.raises(ValueError, match="state transition"):
        run(store.update_job(
            principal_id="owner", job_id=first["job_id"], state="completed"
        ))


def test_comfyui_live_adapter_lifecycle_and_encrypted_result(tmp_path):
    service, store, state = make_service(tmp_path, mode="queued")
    run(service.configure_provider(principal_id="owner", payload=provider_payload()))
    summary = run(service.provider_summary(principal_id="owner"))
    assert summary.configured is True
    assert summary.workflow_id == "basic-v1"
    assert summary.has_bearer_token is True
    assert "base_url" not in summary.model_dump()
    assert "bearer_token" not in summary.model_dump()

    tested = run(service.test_provider(principal_id="owner"))
    assert tested.connection_reachable is True

    job = run(service.submit(
        principal_id="owner",
        idempotency_key=str(uuid4()),
        request=request_payload(),
    ))
    assert job.state == "queued"
    assert state["post_prompt"] == 1

    state["mode"] = "completed"
    completed = run(service.get_job(principal_id="owner", job_id=job.job_id))
    assert completed.state == "completed"
    assert completed.asset is not None
    assert completed.asset.byte_size == len(png_bytes())

    asset = run(service.asset(principal_id="owner", job_id=job.job_id))
    assert asset["bytes"] == png_bytes()
    history = run(service.history(
        principal_id="owner", conversation_id="conversation-a"
    ))
    assert len(history.jobs) == 1
    assert history.jobs[0].state == "completed"

    with pytest.raises(ImageJobNotFound):
        run(service.get_job(principal_id="other-owner", job_id=job.job_id))


def test_submission_unknown_is_never_resubmitted_on_idempotent_retry(tmp_path):
    service, _store, state = make_service(tmp_path, mode="submit-unknown")
    run(service.configure_provider(principal_id="owner", payload=provider_payload()))
    key = str(uuid4())
    first = run(service.submit(
        principal_id="owner", idempotency_key=key, request=request_payload()
    ))
    second = run(service.submit(
        principal_id="owner", idempotency_key=key, request=request_payload()
    ))
    assert first.job_id == second.job_id
    assert first.state == "submission_unknown"
    assert second.state == "submission_unknown"
    assert state["post_prompt"] == 1


def test_only_confirmed_queued_jobs_can_be_cancelled(tmp_path):
    service, _store, state = make_service(tmp_path, mode="queued")
    run(service.configure_provider(principal_id="owner", payload=provider_payload()))
    job = run(service.submit(
        principal_id="owner", idempotency_key=str(uuid4()), request=request_payload()
    ))
    cancelled = run(service.cancel(principal_id="owner", job_id=job.job_id))
    assert cancelled.state == "cancelled"
    assert state["post_queue"] == 1

    service2, _store2, state2 = make_service(tmp_path / "running", mode="running")
    run(service2.configure_provider(principal_id="owner", payload=provider_payload()))
    running_job = run(service2.submit(
        principal_id="owner", idempotency_key=str(uuid4()), request=request_payload()
    ))
    with pytest.raises(ImageJobNotCancellable):
        run(service2.cancel(principal_id="owner", job_id=running_job.job_id))
    assert state2["post_queue"] == 0


def test_comfyui_redirects_and_oversized_responses_are_rejected():
    config = {
        "base_url": "http://127.0.0.1:8188",
        "bearer_token": None,
        "timeout_seconds": 5,
        "workflow": workflow_config().model_dump(mode="json"),
    }

    async def redirect_handler(request):
        return httpx.Response(302, headers={"location": "http://127.0.0.1:8188/other"})

    async def redirect_case():
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(redirect_handler), follow_redirects=False
        ) as client:
            with pytest.raises(ComfyUiFailure) as error:
                await ComfyUiImageAdapter(config, client).test_connection()
            assert error.value.code == "redirect_not_allowed"

    run(redirect_case())

    async def oversized_handler(request):
        return httpx.Response(
            200,
            content=b"{" + (b" " * (1024 * 1024 + 1)) + b"}",
            headers={"content-type": "application/json"},
        )

    async def oversized_case():
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(oversized_handler), follow_redirects=False
        ) as client:
            with pytest.raises(ComfyUiFailure) as error:
                await ComfyUiImageAdapter(config, client).test_connection()
            assert error.value.code == "provider_response_too_large"

    run(oversized_case())


class FakeAuthenticator:
    def authenticate(self, token: str) -> str:
        identities = {"owner-a-token": "owner-a", "owner-b-token": "owner-b"}
        if token not in identities:
            raise HTTPException(status_code=401, detail="Invalid token")
        return identities[token]


class NoopLimiter:
    async def check(self, principal_id):
        return None


class FakeHttpClient:
    async def aclose(self):
        return None


def make_settings():
    return Settings(
        openai_api_key="sk-test-only",
        auth_jwks_url="https://issuer.example/.well-known/jwks.json",
        auth_issuer="https://issuer.example/",
        auth_audience="galaxy-android",
        safety_identifier_hmac_key="test-hmac-secret-long-enough",
        allowed_voices=frozenset({"alloy"}),
        allowed_character_ids=frozenset({"galaxy.default"}),
    )


def test_authenticated_image_api_is_owner_scoped_and_never_returns_provider_secret(tmp_path):
    service, _store, state = make_service(tmp_path, mode="queued")
    app = create_app(
        settings=make_settings(),
        http_client=FakeHttpClient(),
        authenticator=FakeAuthenticator(),
        lila_turn_rate_limiter=NoopLimiter(),
        lila_image_service=service,
    )
    with TestClient(app) as client:
        headers = {"Authorization": "Bearer owner-a-token"}
        configured = client.put(
            "/v1/lila/image-providers/comfyui",
            headers=headers,
            json={
                "base_url": "http://127.0.0.1:8188",
                "bearer_token": "never-return-this-token",
                "workflow": workflow_config().model_dump(mode="json"),
            },
        )
        assert configured.status_code == 200
        assert "never-return-this-token" not in configured.text
        assert "127.0.0.1" not in configured.text

        summary = client.get("/v1/lila/image-providers/comfyui", headers=headers)
        assert summary.status_code == 200
        assert summary.json()["configured"] is True
        assert summary.json().get("bearer_token") is None
        assert "never-return-this-token" not in summary.text
        assert "base_url" not in summary.text

        created = client.post(
            "/v1/lila/images",
            headers={**headers, "Idempotency-Key": str(uuid4())},
            json=request_payload().model_dump(mode="json"),
        )
        assert created.status_code == 200
        job_id = created.json()["job_id"]
        assert created.json()["state"] == "queued"

        history = client.get(
            "/v1/lila/images",
            headers=headers,
            params={"conversation_id": "conversation-a"},
        )
        assert history.status_code == 200
        assert len(history.json()["jobs"]) == 1

        other_headers = {"Authorization": "Bearer owner-b-token"}
        assert client.get(f"/v1/lila/images/{job_id}", headers=other_headers).status_code == 404
        assert client.get(f"/v1/lila/images/{job_id}/asset", headers=other_headers).status_code == 404

        state["mode"] = "completed"
        completed = client.get(f"/v1/lila/images/{job_id}", headers=headers)
        assert completed.status_code == 200
        assert completed.json()["state"] == "completed"
        asset = client.get(f"/v1/lila/images/{job_id}/asset", headers=headers)
        assert asset.status_code == 200
        assert asset.headers["content-type"] == "image/png"
        assert asset.content == png_bytes()


def test_comfyui_credentials_are_not_carried_to_a_new_endpoint(tmp_path):
    service, _store, _state = make_service(tmp_path, mode="queued")
    run(service.configure_provider(principal_id="owner", payload=provider_payload("endpoint-secret")))

    same_endpoint = ComfyUiProviderConfigRequest(
        base_url="http://127.0.0.1:8188",
        workflow=workflow_config(),
    )
    preserved = run(service.configure_provider(principal_id="owner", payload=same_endpoint))
    assert preserved.has_bearer_token is True

    changed_endpoint = ComfyUiProviderConfigRequest(
        base_url="http://localhost:8188",
        workflow=workflow_config(),
    )
    replaced = run(service.configure_provider(principal_id="owner", payload=changed_endpoint))
    assert replaced.has_bearer_token is False
    stored = run(service.store.get_provider_config(principal_id="owner"))
    assert stored["bearer_token"] is None


def test_comfyui_endpoint_rejects_public_and_non_allowlisted_targets(tmp_path):
    service, _store, _state = make_service(tmp_path, mode="queued")
    payload = ComfyUiProviderConfigRequest(
        base_url="https://images.example.net",
        workflow=workflow_config(),
    )
    with pytest.raises(ValueError):
        run(service.configure_provider(principal_id="owner", payload=payload))


def test_image_jobs_share_the_existing_lila_core_database_without_breaking_chat_history(tmp_path):
    path = tmp_path / "lila.sqlite3"
    core_store = SqliteLilaStore(path, KEY)
    run(core_store.append_turn(
        principal_id="owner",
        conversation_id="shared-conversation",
        user_message="Draw an observatory",
        assistant_message="I will prepare an image",
    ))

    image_store = SqliteLilaImageStore(path, KEY)
    run(image_store.save_provider_config(
        principal_id="owner",
        config={
            "base_url": "http://127.0.0.1:8188",
            "bearer_token": None,
            "timeout_seconds": 30.0,
            "workflow": workflow_config().model_dump(mode="json"),
        },
    ))

    reopened_core = SqliteLilaStore(path, KEY)
    messages = run(reopened_core.load_recent_messages(
        principal_id="owner", conversation_id="shared-conversation", limit=10
    ))
    assert [message.content for message in messages] == [
        "Draw an observatory",
        "I will prepare an image",
    ]
