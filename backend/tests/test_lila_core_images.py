import asyncio
import hashlib
import json
import os
import sqlite3
import struct
import threading
import time
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from uuid import uuid4

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
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


class LiveJwksServer:
    """Ephemeral RS256 issuer for production-authenticator integration tests."""

    def __init__(self, issuer: str, audience: str) -> None:
        self.issuer = issuer
        self.audience = audience
        self.private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.key_id = uuid4().hex
        public_jwk = json.loads(
            jwt.algorithms.RSAAlgorithm.to_jwk(self.private_key.public_key())
        )
        public_jwk.update({"kid": self.key_id, "use": "sig", "alg": "RS256"})
        jwks = {"keys": [public_jwk]}

        class JwksHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != "/.well-known/jwks.json":
                    self.send_error(404)
                    return
                payload = json.dumps(jwks, separators=(",", ":")).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, format, *args):
                return

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), JwksHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self) -> str:
        return (
            "http://127.0.0.1:"
            + str(self.server.server_port)
            + "/.well-known/jwks.json"
        )

    def token_for(self, subject: str) -> str:
        now = int(time.time())
        return jwt.encode(
            {
                "sub": subject,
                "iss": self.issuer,
                "aud": self.audience,
                "iat": now,
                "exp": now + 600,
            },
            self.private_key,
            algorithm="RS256",
            headers={"kid": self.key_id},
        )

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


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


def sdxl_workflow_config(checkpoint="models/sdxl.safetensors"):
    graph = {
        "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": checkpoint}},
        "5": {"class_type": "CLIPTextEncode", "inputs": {"text": "", "clip": ["4", 1]}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "", "clip": ["4", 1]}},
        "7": {"class_type": "EmptyLatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
        "3": {
            "class_type": "KSampler",
            "inputs": {
                "seed": 0, "steps": 30, "cfg": 5.5, "sampler_name": "dpmpp_2m",
                "scheduler": "karras", "denoise": 1.0, "model": ["4", 0],
                "positive": ["5", 0], "negative": ["6", 0], "latent_image": ["7", 0],
            },
        },
        "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
        "9": {"class_type": "SaveImage", "inputs": {"images": ["8", 0], "filename_prefix": "lila"}},
    }
    return ComfyUiWorkflowConfig(
        workflow_id="lila.sdxl.v1",
        workflow_json=json.dumps(graph, separators=(",", ":")),
        positive_prompt={"node_id": "5", "input_name": "text"},
        negative_prompt={"node_id": "6", "input_name": "text"},
        width={"node_id": "7", "input_name": "width"},
        height={"node_id": "7", "input_name": "height"},
        seed={"node_id": "3", "input_name": "seed"},
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
    state = {
        "mode": mode,
        "prompt_id": None,
        "post_prompt": 0,
        "post_queue": 0,
        "requests": [],
        "checkpoint_names": ["sdxl.safetensors", "models/sdxl-refiner.safetensors"],
        "checkpoint_discovery_status": 200,
    }
    image = png_bytes()

    async def handler(request: httpx.Request):
        state["requests"].append((request.method, request.url.path))
        if request.url.path == "/system_stats":
            return httpx.Response(200, json={"system": {}, "devices": []})
        if request.url.path == "/object_info/CheckpointLoaderSimple":
            discovery_status = state["checkpoint_discovery_status"]
            if discovery_status != 200:
                return httpx.Response(discovery_status, json={})
            return httpx.Response(200, json={
                "CheckpointLoaderSimple": {
                    "input": {"required": {"ckpt_name": [state["checkpoint_names"], {}]}}
                }
            })
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


def test_builtin_sdxl_workflow_validates_graph_and_builds_prompt():
    workflow = sdxl_workflow_config()
    request = ImageGenerationRequest(
        conversation_id="sdxl-conversation",
        prompt="A moonlit observatory",
        negative_prompt="blurry",
        width=1024,
        height=1024,
        seed=42,
    )
    graph = workflow.build_prompt(request)
    assert graph["4"]["inputs"]["ckpt_name"] == "models/sdxl.safetensors"
    assert graph["5"]["inputs"]["text"] == "A moonlit observatory"
    assert graph["6"]["inputs"]["text"] == "blurry"
    assert graph["3"]["inputs"]["seed"] == 42
    assert graph["7"]["inputs"]["width"] == 1024
    assert graph["7"]["inputs"]["height"] == 1024


def test_builtin_sdxl_workflow_rejects_disconnected_or_non_sdxl_graph():
    graph = json.loads(sdxl_workflow_config().workflow_json)
    graph["9"]["inputs"]["images"] = ["3", 0]
    with pytest.raises(ValueError, match="built-in SDXL workflow"):
        ComfyUiWorkflowConfig(
            workflow_id="lila.sdxl.v1",
            workflow_json=json.dumps(graph),
            positive_prompt={"node_id": "5", "input_name": "text"},
            negative_prompt={"node_id": "6", "input_name": "text"},
            width={"node_id": "7", "input_name": "width"},
            height={"node_id": "7", "input_name": "height"},
            seed={"node_id": "3", "input_name": "seed"},
        )

    graph = json.loads(sdxl_workflow_config().workflow_json)
    graph["3"]["inputs"]["sampler_name"] = "euler"
    with pytest.raises(ValueError, match="built-in SDXL workflow"):
        ComfyUiWorkflowConfig(
            workflow_id="lila.sdxl.v1",
            workflow_json=json.dumps(graph),
            positive_prompt={"node_id": "5", "input_name": "text"},
            negative_prompt={"node_id": "6", "input_name": "text"},
            width={"node_id": "7", "input_name": "width"},
            height={"node_id": "7", "input_name": "height"},
            seed={"node_id": "3", "input_name": "seed"},
        )


def test_builtin_sdxl_workflow_rejects_unsafe_checkpoint_and_wrong_mappings():
    with pytest.raises(ValueError, match="unsafe checkpoint"):
        sdxl_workflow_config("../outside.safetensors")

    with pytest.raises(ValueError, match="built-in SDXL workflow mappings"):
        ComfyUiWorkflowConfig(
            workflow_id="lila.sdxl.v1",
            workflow_json=sdxl_workflow_config().workflow_json,
            positive_prompt={"node_id": "6", "input_name": "text"},
            negative_prompt={"node_id": "6", "input_name": "text"},
            width={"node_id": "7", "input_name": "width"},
            height={"node_id": "7", "input_name": "height"},
            seed={"node_id": "3", "input_name": "seed"},
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
    assert tested.checkpoint_discovery_supported is True
    assert tested.available_checkpoints == ["sdxl.safetensors", "models/sdxl-refiner.safetensors"]
    assert tested.checkpoint_list_truncated is False
    assert "bearer_token" not in tested.model_dump()
    assert "base_url" not in tested.model_dump()

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


def test_comfyui_checkpoint_discovery_filters_paths_and_caps_results(tmp_path):
    service, _store, state = make_service(tmp_path, mode="queued")
    run(service.configure_provider(principal_id="owner", payload=provider_payload()))
    state["checkpoint_names"] = [
        "sdxl.safetensors",
        "models/sdxl-refiner.safetensors",
        "../outside.safetensors",
        "/outside.safetensors",
        "models/../outside.safetensors",
        "bad\\path.safetensors",
        "",
        "x" * 513,
        "sdxl.safetensors",
        *[f"extra-{index}.safetensors" for index in range(300)],
    ]

    result = run(service.test_provider(principal_id="owner"))

    assert result.connection_reachable is True
    assert result.checkpoint_discovery_supported is True
    assert result.available_checkpoints[:2] == [
        "sdxl.safetensors",
        "models/sdxl-refiner.safetensors",
    ]
    assert len(result.available_checkpoints) == 256
    assert result.checkpoint_list_truncated is True
    assert all(".." not in name.split("/") for name in result.available_checkpoints)
    assert all("\\" not in name for name in result.available_checkpoints)


def test_comfyui_checkpoint_discovery_is_optional_for_older_runtimes(tmp_path):
    service, _store, state = make_service(tmp_path, mode="queued")
    run(service.configure_provider(principal_id="owner", payload=provider_payload()))
    state["checkpoint_discovery_status"] = 404

    result = run(service.test_provider(principal_id="owner"))

    assert result.connection_reachable is True
    assert result.checkpoint_discovery_supported is False
    assert result.available_checkpoints == []
    assert result.checkpoint_list_truncated is False

def test_prepared_image_job_is_submitted_after_service_restart(tmp_path):
    service, store, state = make_service(tmp_path, mode="queued")
    run(service.configure_provider(principal_id="owner", payload=provider_payload()))
    request = request_payload()
    provider_config = run(store.get_provider_config(principal_id="owner"))
    job, created = run(store.create_job(
        principal_id="owner",
        conversation_id=request.conversation_id,
        idempotency_key=str(uuid4()),
        request=request.model_dump(mode="json", exclude={"conversation_id"}),
        provider_config=provider_config,
    ))
    assert created is True
    assert job["state"] == "prepared"
    assert state["post_prompt"] == 0

    restarted_store = SqliteLilaImageStore(tmp_path / "lila.sqlite3", KEY)
    restarted = LilaImageGenerationService(
        restarted_store, client_factory=service.client_factory
    )
    recovered = run(restarted.get_job(principal_id="owner", job_id=job["job_id"]))
    assert recovered.state == "queued"
    assert state["post_prompt"] == 1

    repeated = run(restarted.get_job(principal_id="owner", job_id=job["job_id"]))
    assert repeated.state == "queued"
    assert state["post_prompt"] == 1


def test_interrupted_submission_is_not_retried_after_service_restart(tmp_path):
    service, store, state = make_service(tmp_path, mode="queued")
    run(service.configure_provider(principal_id="owner", payload=provider_payload()))
    request = request_payload()
    idempotency_key = str(uuid4())
    provider_config = run(store.get_provider_config(principal_id="owner"))
    job, created = run(store.create_job(
        principal_id="owner",
        conversation_id=request.conversation_id,
        idempotency_key=idempotency_key,
        request=request.model_dump(mode="json", exclude={"conversation_id"}),
        provider_config=provider_config,
    ))
    assert created is True
    assert run(store.claim_submission(principal_id="owner", job_id=job["job_id"])) is True
    assert state["post_prompt"] == 0

    restarted_store = SqliteLilaImageStore(tmp_path / "lila.sqlite3", KEY)
    restarted = LilaImageGenerationService(
        restarted_store, client_factory=service.client_factory
    )
    recovered = run(restarted.get_job(principal_id="owner", job_id=job["job_id"]))
    assert recovered.state == "submission_unknown"
    assert state["post_prompt"] == 0

    repeated = run(restarted.submit(
        principal_id="owner",
        idempotency_key=idempotency_key,
        request=request,
    ))
    assert repeated.job_id == job["job_id"]
    assert repeated.state == "submission_unknown"
    assert state["post_prompt"] == 0


def test_queued_image_job_recovers_and_persists_result_after_service_restart(tmp_path):
    service, _store, state = make_service(tmp_path, mode="queued")
    run(service.configure_provider(principal_id="owner", payload=provider_payload()))
    job = run(service.submit(
        principal_id="owner",
        idempotency_key=str(uuid4()),
        request=request_payload(),
    ))
    assert job.state == "queued"
    assert state["post_prompt"] == 1

    restarted_store = SqliteLilaImageStore(tmp_path / "lila.sqlite3", KEY)
    restarted = LilaImageGenerationService(
        restarted_store, client_factory=service.client_factory
    )
    state["mode"] = "completed"
    recovered = run(restarted.get_job(principal_id="owner", job_id=job.job_id))
    assert recovered.state == "completed"
    assert recovered.asset is not None
    assert state["post_prompt"] == 1

    asset = run(restarted.asset(principal_id="owner", job_id=job.job_id))
    assert asset["bytes"] == png_bytes()
    history = run(restarted.history(
        principal_id="owner", conversation_id="conversation-a"
    ))
    assert len(history.jobs) == 1
    assert history.jobs[0].job_id == job.job_id
    assert history.jobs[0].state == "completed"

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


class RejectingLimiter:
    async def check(self, principal_id):
        raise HTTPException(
            status_code=429,
            detail="Test rate limit exceeded",
            headers={"Retry-After": "60"},
        )


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

        configuration = client.get(
            "/v1/lila/image-providers/comfyui/config", headers=headers
        )
        assert configuration.status_code == 200
        assert configuration.headers["cache-control"] == "no-store"
        config_json = configuration.json()
        assert config_json["configured"] is True
        assert config_json["base_url"] == "http://127.0.0.1:8188"
        assert config_json["workflow"]["workflow_id"] == "basic-v1"
        assert config_json["has_bearer_token"] is True
        assert "never-return-this-token" not in configuration.text

        other_configuration = client.get(
            "/v1/lila/image-providers/comfyui/config",
            headers={"Authorization": "Bearer owner-b-token"},
        )
        assert other_configuration.status_code == 200
        assert other_configuration.json()["configured"] is False
        assert other_configuration.json()["base_url"] is None
        assert other_configuration.json()["workflow"] is None
        assert "never-return-this-token" not in other_configuration.text

        created = client.post(
            "/v1/lila/images",
            headers={**headers, "Idempotency-Key": str(uuid4())},
            json=request_payload().model_dump(mode="json"),
        )
        assert created.status_code == 200
        job_id = created.json()["job_id"]
        assert created.json()["state"] == "queued"

        created_second = client.post(
            "/v1/lila/images",
            headers={**headers, "Idempotency-Key": str(uuid4())},
            json=request_payload(prompt="A second observatory").model_dump(mode="json"),
        )
        assert created_second.status_code == 200

        history = client.get(
            "/v1/lila/images",
            headers=headers,
            params={"conversation_id": "conversation-a", "limit": 1},
        )
        assert history.status_code == 200
        assert len(history.json()["jobs"]) == 1
        cursor = history.json()["next_cursor"]
        assert isinstance(cursor, str) and cursor

        older_history = client.get(
            "/v1/lila/images",
            headers=headers,
            params={"conversation_id": "conversation-a", "limit": 1, "cursor": cursor},
        )
        assert older_history.status_code == 200
        assert len(older_history.json()["jobs"]) == 1
        assert older_history.json()["next_cursor"] is None

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


def test_image_read_routes_use_a_dedicated_owner_rate_limiter(tmp_path):
    service, _store, _state = make_service(tmp_path, mode="queued")
    run(service.configure_provider(principal_id="owner-a", payload=provider_payload()))
    job = run(service.submit(
        principal_id="owner-a",
        idempotency_key=str(uuid4()),
        request=request_payload(),
    ))
    app = create_app(
        settings=make_settings(),
        http_client=FakeHttpClient(),
        authenticator=FakeAuthenticator(),
        lila_turn_rate_limiter=NoopLimiter(),
        lila_image_read_rate_limiter=RejectingLimiter(),
        lila_image_service=service,
    )
    with TestClient(app) as client:
        headers = {"Authorization": "Bearer owner-a-token"}
        paths = [
            "/v1/lila/image-providers/comfyui",
            "/v1/lila/image-providers/comfyui/config",
            "/v1/lila/images?conversation_id=conversation-a",
            f"/v1/lila/images/{job.job_id}",
            f"/v1/lila/images/{job.job_id}/asset",
        ]
        for path in paths:
            response = client.get(path, headers=headers)
            assert response.status_code == 429, path
            assert response.headers["retry-after"] == "60"


def test_image_status_poll_uses_read_quota_while_cancel_uses_turn_quota(tmp_path):
    service, _store, _state = make_service(tmp_path, mode="queued")
    run(service.configure_provider(principal_id="owner-a", payload=provider_payload()))
    job = run(service.submit(
        principal_id="owner-a",
        idempotency_key=str(uuid4()),
        request=request_payload(),
    ))
    app = create_app(
        settings=make_settings(),
        http_client=FakeHttpClient(),
        authenticator=FakeAuthenticator(),
        lila_turn_rate_limiter=RejectingLimiter(),
        lila_image_read_rate_limiter=NoopLimiter(),
        lila_image_service=service,
    )
    with TestClient(app) as client:
        headers = {"Authorization": "Bearer owner-a-token"}
        polled = client.get(f"/v1/lila/images/{job.job_id}", headers=headers)
        assert polled.status_code == 200
        cancelled = client.post(f"/v1/lila/images/{job.job_id}/cancel", headers=headers)
        assert cancelled.status_code == 429
        assert cancelled.headers["retry-after"] == "60"


def test_image_history_cursor_is_stable_when_newer_jobs_are_added(tmp_path):
    service, _store, _state = make_service(tmp_path, mode="queued")
    run(service.configure_provider(principal_id="owner", payload=provider_payload()))
    created_ids = []
    for index in range(3):
        job = run(service.submit(
            principal_id="owner",
            idempotency_key=str(uuid4()),
            request=request_payload(prompt=f"observatory {index}"),
        ))
        created_ids.append(job.job_id)
        run(asyncio.sleep(0.005))

    first_page = run(service.history(
        principal_id="owner", conversation_id="conversation-a", limit=2
    ))
    assert [job.job_id for job in first_page.jobs] == [created_ids[2], created_ids[1]]
    assert first_page.next_cursor is not None

    run(service.submit(
        principal_id="owner",
        idempotency_key=str(uuid4()),
        request=request_payload(prompt="newest observatory"),
    ))
    second_page = run(service.history(
        principal_id="owner",
        conversation_id="conversation-a",
        limit=2,
        cursor=first_page.next_cursor,
    ))
    assert [job.job_id for job in second_page.jobs] == [created_ids[0]]
    assert second_page.next_cursor is None

    with pytest.raises(ValueError, match="cursor is invalid"):
        run(service.history(
            principal_id="another-owner",
            conversation_id="conversation-a",
            limit=2,
            cursor=first_page.next_cursor,
        ))


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


@pytest.mark.skipif(
    not all(os.environ.get(name) for name in ("LILA_LIVE_COMFYUI_URL", "REDIS_URL")),
    reason="requires the pinned ComfyUI CPU runtime and ephemeral Redis",
)
def test_live_comfyui_core_image_lifecycle_and_encrypted_history_survive_restart(tmp_path):
    """Exercise real ComfyUI with production JWT/JWKS and Redis image quotas.

    EmptyImage -> SaveImage is a deterministic runtime smoke workflow, not a
    text-to-image quality test. The test uses the production app composition,
    JwtAuthenticator and Redis-backed write/read limiters.
    """
    base_url = os.environ["LILA_LIVE_COMFYUI_URL"].rstrip("/")
    parsed = urlsplit(base_url)
    assert (
        parsed.scheme,
        parsed.hostname,
        parsed.port,
        parsed.path,
        parsed.query,
        parsed.fragment,
    ) == ("http", "127.0.0.1", 8188, "", "", "")
    redis_url = os.environ["REDIS_URL"]

    issuer = "https://lila-live-comfyui.example/"
    audience = "galaxy-android"
    jwks = LiveJwksServer(issuer, audience)
    database_path = tmp_path / "lila-live-comfyui.sqlite3"
    settings = Settings(
        openai_api_key=None,
        auth_jwks_url=jwks.url,
        auth_issuer=issuer,
        auth_audience=audience,
        safety_identifier_hmac_key=None,
        allowed_voices=frozenset({"alloy"}),
        allowed_character_ids=frozenset({"galaxy.default"}),
        environment="production",
        redis_url=redis_url,
        lila_database_path=str(database_path),
        lila_storage_encryption_key=os.urandom(32),
    )
    owner_a = "comfyui-owner-a-" + uuid4().hex
    owner_b = "comfyui-owner-b-" + uuid4().hex
    auth_headers = {"Authorization": "Bearer " + jwks.token_for(owner_a)}
    other_owner_headers = {"Authorization": "Bearer " + jwks.token_for(owner_b)}
    conversation_id = str(uuid4())
    prompt = "lila-comfyui-ci-" + uuid4().hex
    workflow = {
        "workflow_id": "lila-ci-empty-image-v1",
        "workflow_json": json.dumps(
            {
                "1": {
                    "class_type": "EmptyImage",
                    "inputs": {
                        "width": 64,
                        "height": 64,
                        "batch_size": 1,
                        "color": 3368652,
                    },
                },
                "2": {
                    "class_type": "SaveImage",
                    "inputs": {
                        "images": ["1", 0],
                        "filename_prefix": "lila-comfyui-ci",
                    },
                },
            },
            separators=(",", ":"),
        ),
        "positive_prompt": {"node_id": "2", "input_name": "filename_prefix"},
        "width": {"node_id": "1", "input_name": "width"},
        "height": {"node_id": "1", "input_name": "height"},
    }

    def new_client(active_settings=settings):
        return TestClient(create_app(settings=active_settings))

    try:
        with new_client() as client:
            assert client.get("/v1/lila/image-providers/comfyui").status_code == 401
            invalid_token = client.get(
                "/v1/lila/image-providers/comfyui",
                headers={"Authorization": "Bearer invalid-token"},
            )
            assert invalid_token.status_code == 401

            configured = client.put(
                "/v1/lila/image-providers/comfyui",
                headers=auth_headers,
                json={"base_url": base_url, "workflow": workflow},
            )
            assert configured.status_code == 200, configured.text
            assert configured.json()["configured"] is True
            assert configured.json()["has_bearer_token"] is False

            connection = client.post(
                "/v1/lila/image-providers/comfyui/test",
                headers=auth_headers,
            )
            assert connection.status_code == 200, connection.text
            assert connection.json()["connection_reachable"] is True

            created = client.post(
                "/v1/lila/images",
                headers={**auth_headers, "Idempotency-Key": str(uuid4())},
                json={
                    "conversation_id": conversation_id,
                    "prompt": prompt,
                    "negative_prompt": "",
                    "width": 64,
                    "height": 64,
                },
            )
            assert created.status_code == 200, created.text
            job_id = created.json()["job_id"]
            assert created.json()["state"] in {"queued", "running", "completed"}

            deadline = time.monotonic() + 120
            current = created.json()
            while time.monotonic() < deadline:
                refreshed = client.get(
                    f"/v1/lila/images/{job_id}",
                    headers=auth_headers,
                )
                assert refreshed.status_code == 200, refreshed.text
                current = refreshed.json()
                if current["state"] in {"completed", "failed", "cancelled"}:
                    break
                time.sleep(0.5)
            assert current["state"] == "completed", current

            history = client.get(
                "/v1/lila/images",
                headers=auth_headers,
                params={"conversation_id": conversation_id, "limit": 50},
            )
            assert history.status_code == 200, history.text
            jobs = history.json()["jobs"]
            assert len(jobs) == 1
            assert jobs[0]["job_id"] == job_id
            assert jobs[0]["state"] == "completed"
            metadata = jobs[0]["asset"]
            assert metadata["mime_type"] == "image/png"
            assert metadata["width"] == 64
            assert metadata["height"] == 64

            asset = client.get(
                f"/v1/lila/images/{job_id}/asset",
                headers=auth_headers,
            )
            assert asset.status_code == 200, asset.text
            assert asset.headers["content-type"] == "image/png"
            asset_bytes = asset.content
            png_signature = bytes.fromhex("89504e470d0a1a0a")
            assert asset_bytes.startswith(png_signature)
            assert struct.unpack(">II", asset_bytes[16:24]) == (64, 64)
            assert hashlib.sha256(asset_bytes).hexdigest() == metadata["sha256"]
            assert len(asset_bytes) == metadata["byte_size"]

            assert client.get(
                f"/v1/lila/images/{job_id}",
                headers=other_owner_headers,
            ).status_code == 404
            assert client.get(
                f"/v1/lila/images/{job_id}/asset",
                headers=other_owner_headers,
            ).status_code == 404
            other_history = client.get(
                "/v1/lila/images",
                headers=other_owner_headers,
                params={"conversation_id": conversation_id, "limit": 50},
            )
            assert other_history.status_code == 200
            assert other_history.json()["jobs"] == []
            other_provider = client.get(
                "/v1/lila/image-providers/comfyui",
                headers=other_owner_headers,
            )
            assert other_provider.status_code == 200
            assert other_provider.json()["configured"] is False

        with sqlite3.connect(database_path) as connection:
            job_ciphertexts = connection.execute(
                "SELECT ciphertext FROM lila_image_jobs"
            ).fetchall()
            asset_ciphertexts = connection.execute(
                "SELECT ciphertext FROM lila_image_assets"
            ).fetchall()
        assert job_ciphertexts
        assert asset_ciphertexts
        assert all(prompt.encode("utf-8") not in bytes(row[0]) for row in job_ciphertexts)
        assert all(png_signature not in bytes(row[0]) for row in asset_ciphertexts)

        with new_client() as restarted:
            restored_history = restarted.get(
                "/v1/lila/images",
                headers=auth_headers,
                params={"conversation_id": conversation_id, "limit": 50},
            )
            assert restored_history.status_code == 200, restored_history.text
            restored_jobs = restored_history.json()["jobs"]
            assert len(restored_jobs) == 1
            assert restored_jobs[0]["job_id"] == job_id
            assert restored_jobs[0]["state"] == "completed"

            restored_asset = restarted.get(
                f"/v1/lila/images/{job_id}/asset",
                headers=auth_headers,
            )
            assert restored_asset.status_code == 200, restored_asset.text
            assert restored_asset.content == asset_bytes
            assert restarted.get(
                f"/v1/lila/images/{job_id}/asset",
                headers=other_owner_headers,
            ).status_code == 404

        # Verify the production Redis-backed image-read quota is owner-scoped.
        read_limited_settings = replace(settings, lila_image_reads_per_day=1)
        read_owner_headers = {
            "Authorization": "Bearer " + jwks.token_for("read-limit-" + uuid4().hex)
        }
        read_other_headers = {
            "Authorization": "Bearer " + jwks.token_for("read-other-" + uuid4().hex)
        }
        with new_client(read_limited_settings) as limited:
            first_read = limited.get(
                "/v1/lila/image-providers/comfyui",
                headers=read_owner_headers,
            )
            other_read = limited.get(
                "/v1/lila/image-providers/comfyui",
                headers=read_other_headers,
            )
            blocked_read = limited.get(
                "/v1/lila/image-providers/comfyui",
                headers=read_owner_headers,
            )
            assert first_read.status_code == 200, first_read.text
            assert other_read.status_code == 200, other_read.text
            assert blocked_read.status_code == 429, blocked_read.text
            assert 1 <= int(blocked_read.headers["retry-after"]) <= 86400

        # Image mutations share the production Lila-turn Redis quota.
        write_limited_settings = replace(settings, lila_turns_per_day=1)
        write_owner_headers = {
            "Authorization": "Bearer " + jwks.token_for("write-limit-" + uuid4().hex)
        }
        write_other_headers = {
            "Authorization": "Bearer " + jwks.token_for("write-other-" + uuid4().hex)
        }
        provider_payload = {"base_url": base_url, "workflow": workflow}
        with new_client(write_limited_settings) as limited:
            first_write = limited.put(
                "/v1/lila/image-providers/comfyui",
                headers=write_owner_headers,
                json=provider_payload,
            )
            other_write = limited.put(
                "/v1/lila/image-providers/comfyui",
                headers=write_other_headers,
                json=provider_payload,
            )
            blocked_write = limited.put(
                "/v1/lila/image-providers/comfyui",
                headers=write_owner_headers,
                json=provider_payload,
            )
            assert first_write.status_code == 200, first_write.text
            assert other_write.status_code == 200, other_write.text
            assert blocked_write.status_code == 429, blocked_write.text
            assert 1 <= int(blocked_write.headers["retry-after"]) <= 86400
    finally:
        jwks.close()
