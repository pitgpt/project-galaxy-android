"""Provider-neutral Lila image generation with a bounded local ComfyUI adapter."""
from __future__ import annotations

import asyncio
import json
import re
from contextlib import asynccontextmanager
from hashlib import sha256
from typing import Any, Literal, Protocol
from urllib.parse import quote
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator, model_validator

from .provider_egress import create_provider_http_client
from .provider_registry import validate_provider_endpoint
from .image_store import (
    ImageIdempotencyConflict,
    ImageJobNotFound,
    SqliteLilaImageStore,
)


MAX_WORKFLOW_JSON_CHARS = 2 * 1024 * 1024
MAX_COMFYUI_JSON_BYTES = 1024 * 1024
MAX_COMFYUI_IMAGE_BYTES = 25 * 1024 * 1024
COMFYUI_LOCAL_PORTS = frozenset({8188})


class ImageProviderNotConfigured(RuntimeError):
    pass


class ImageJobNotCancellable(RuntimeError):
    pass


class ComfyUiFailure(RuntimeError):
    def __init__(self, code: str, *, status_code: int | None = None, definitely_rejected: bool = False):
        super().__init__(code)
        self.code = code
        self.status_code = status_code
        self.definitely_rejected = definitely_rejected


class ComfyUiNodeInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    node_id: str = Field(pattern=r"^[0-9]{1,12}$")
    input_name: str = Field(pattern=r"^[A-Za-z0-9_]{1,64}$")


class ComfyUiWorkflowConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workflow_id: str = Field(min_length=1, max_length=96, pattern=r"^[A-Za-z0-9_.-]+$")
    workflow_json: str = Field(min_length=2, max_length=MAX_WORKFLOW_JSON_CHARS)
    positive_prompt: ComfyUiNodeInput
    negative_prompt: ComfyUiNodeInput | None = None
    width: ComfyUiNodeInput | None = None
    height: ComfyUiNodeInput | None = None
    seed: ComfyUiNodeInput | None = None

    @field_validator("workflow_json")
    @classmethod
    def validate_workflow_size(cls, value: str) -> str:
        if len(value.encode("utf-8")) > MAX_WORKFLOW_JSON_CHARS:
            raise ValueError("workflow_json exceeds the byte limit")
        return value

    @model_validator(mode="after")
    def validate_graph_mappings(self):
        try:
            graph = json.loads(self.workflow_json)
        except Exception:
            raise ValueError("workflow_json must be valid JSON") from None
        if not isinstance(graph, dict) or not graph:
            raise ValueError("workflow_json must contain a non-empty ComfyUI API graph")
        for mapping in (
            self.positive_prompt, self.negative_prompt, self.width, self.height, self.seed
        ):
            if mapping is None:
                continue
            node = graph.get(mapping.node_id)
            inputs = node.get("inputs") if isinstance(node, dict) else None
            if not isinstance(inputs, dict) or mapping.input_name not in inputs:
                raise ValueError("workflow_json is missing a mapped node input")
        return self

    def build_prompt(self, request: "ImageGenerationRequest") -> dict[str, Any]:
        graph = json.loads(self.workflow_json)
        mappings: tuple[tuple[ComfyUiNodeInput | None, Any], ...] = (
            (self.positive_prompt, request.prompt),
            (self.negative_prompt, request.negative_prompt or ""),
            (self.width, request.width),
            (self.height, request.height),
            (self.seed, request.seed),
        )
        for mapping, value in mappings:
            if mapping is not None and value is not None:
                graph[mapping.node_id]["inputs"][mapping.input_name] = value
        return graph


class ComfyUiProviderConfigRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    base_url: str = Field(min_length=1, max_length=2048)
    bearer_token: SecretStr | None = None
    clear_bearer_token: bool = False
    timeout_seconds: float = Field(default=30.0, ge=1.0, le=30.0)
    workflow: ComfyUiWorkflowConfig

    @model_validator(mode="after")
    def validate_token_update(self):
        if self.clear_bearer_token and self.bearer_token is not None:
            raise ValueError("bearer_token and clear_bearer_token cannot be used together")
        return self

    @field_validator("bearer_token")
    @classmethod
    def validate_token(cls, value: SecretStr | None) -> SecretStr | None:
        if value is not None:
            secret = value.get_secret_value()
            if not secret.strip() or len(secret) > 4096:
                raise ValueError("bearer_token length is invalid")
        return value


class ImageProviderSummary(BaseModel):
    model_config = ConfigDict(extra="forbid")

    provider_id: Literal["comfyui"] = "comfyui"
    configured: bool
    workflow_id: str | None = None
    has_bearer_token: bool = False
    supports_queued_cancellation: bool = True
    supports_running_cancellation: Literal[False] = False


class ImageProviderConnectionTestResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    provider_id: Literal["comfyui"] = "comfyui"
    connection_reachable: bool
    workflow_id: str


class ImageGenerationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    conversation_id: str = Field(min_length=1, max_length=128)
    prompt: str = Field(min_length=1, max_length=12_000)
    negative_prompt: str = Field(default="", max_length=4_000)
    width: int = Field(default=1024, ge=64, le=2048)
    height: int = Field(default=1024, ge=64, le=2048)
    seed: int | None = Field(default=None, ge=0, le=4_294_967_295)

    @field_validator("prompt")
    @classmethod
    def validate_prompt(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("prompt must contain non-whitespace characters")
        return value


class ImageAssetSummary(BaseModel):
    model_config = ConfigDict(extra="forbid")

    mime_type: Literal["image/png"]
    width: int = Field(ge=1, le=8192)
    height: int = Field(ge=1, le=8192)
    byte_size: int = Field(ge=1, le=MAX_COMFYUI_IMAGE_BYTES)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


class ImageGenerationJobResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    job_id: str
    conversation_id: str
    prompt: str
    negative_prompt: str
    width: int
    height: int
    seed: int | None = None
    state: Literal[
        "prepared", "submitting", "submission_unknown", "queued", "running",
        "provider_completed", "result_pending", "completed", "failed", "cancelled",
    ]
    created_at_epoch_millis: int
    updated_at_epoch_millis: int
    asset: ImageAssetSummary | None = None
    error_code: str | None = Field(default=None, max_length=64)
    supports_queued_cancellation: bool = True
    supports_running_cancellation: Literal[False] = False


class ImageGenerationHistoryResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    conversation_id: str
    jobs: list[ImageGenerationJobResponse] = Field(default_factory=list, max_length=50)


class ComfyUiImageReference(BaseModel):
    model_config = ConfigDict(extra="forbid")

    filename: str = Field(min_length=1, max_length=255)
    subfolder: str = Field(default="", max_length=1024)
    type: Literal["output"] = "output"

    @model_validator(mode="after")
    def validate_reference(self):
        if (
            self.filename in {".", ".."}
            or any(char in self.filename for char in ("/", "\\"))
            or any(ord(char) < 32 or ord(char) == 127 for char in self.filename)
            or self.subfolder.startswith("/")
            or "\\" in self.subfolder
            or any(part in {".", ".."} for part in self.subfolder.split("/"))
        ):
            raise ValueError("ComfyUI image reference is unsafe")
        return self


class ImageProviderAdapter(Protocol):
    """Provider-neutral image lifecycle contract used by Lila Core."""

    async def test_connection(self) -> None: ...
    async def submit(self, request: ImageGenerationRequest, prompt_id: str) -> None: ...
    async def status(self, prompt_id: str) -> tuple[str, list[ComfyUiImageReference]]: ...
    async def cancel_queued(self, prompt_id: str) -> bool: ...
    async def download_result(self, prompt_id: str) -> tuple[bytes, int, int]: ...


class ComfyUiImageAdapter:
    """ComfyUI adapter using the shared DNS-pinned, no-proxy, no-redirect transport."""

    def __init__(self, config: dict[str, Any], client: httpx.AsyncClient):
        self.config = config
        self.client = client
        self.base_url = str(config["base_url"]).rstrip("/")
        self.bearer_token = config.get("bearer_token")
        self.timeout_seconds = float(config.get("timeout_seconds", 30.0))
        self.workflow = ComfyUiWorkflowConfig.model_validate(config["workflow"])

    def _headers(self) -> dict[str, str]:
        headers = {"Accept": "application/json, image/png"}
        if self.bearer_token:
            headers["Authorization"] = "Bearer " + str(self.bearer_token)
        return headers

    async def _request_bytes(
        self,
        method: str,
        path: str,
        *,
        json_body: dict[str, Any] | None = None,
        params: dict[str, str] | None = None,
        max_bytes: int = MAX_COMFYUI_JSON_BYTES,
    ) -> tuple[bytes, str]:
        url = self.base_url + path
        try:
            async with self.client.stream(
                method,
                url,
                json=json_body,
                params=params,
                headers=self._headers(),
                timeout=self.timeout_seconds,
            ) as response:
                if 300 <= response.status_code < 400:
                    raise ComfyUiFailure("redirect_not_allowed", status_code=response.status_code)
                if not 200 <= response.status_code < 300:
                    status_code = response.status_code
                    definitely_rejected = 400 <= status_code < 500 and status_code not in {408, 409, 429}
                    raise ComfyUiFailure(
                        "provider_rejected" if definitely_rejected else "provider_outcome_unknown",
                        status_code=status_code,
                        definitely_rejected=definitely_rejected,
                    )
                length = response.headers.get("content-length")
                if length is not None:
                    try:
                        if int(length) > max_bytes:
                            raise ComfyUiFailure("provider_response_too_large")
                    except ValueError:
                        raise ComfyUiFailure("provider_invalid_response") from None
                chunks: list[bytes] = []
                total = 0
                async for chunk in response.aiter_bytes():
                    total += len(chunk)
                    if total > max_bytes:
                        raise ComfyUiFailure("provider_response_too_large")
                    chunks.append(chunk)
                return b"".join(chunks), response.headers.get("content-type", "")
        except ComfyUiFailure:
            raise
        except (httpx.TimeoutException, httpx.TransportError):
            raise ComfyUiFailure("provider_outcome_unknown") from None
        except Exception:
            raise ComfyUiFailure("provider_invalid_response") from None

    async def _json(self, method: str, path: str, *, body: dict[str, Any] | None = None,
                    params: dict[str, str] | None = None) -> dict[str, Any]:
        raw, _ = await self._request_bytes(method, path, json_body=body, params=params)
        try:
            value = json.loads(raw)
        except Exception:
            raise ComfyUiFailure("provider_invalid_response") from None
        if not isinstance(value, dict):
            raise ComfyUiFailure("provider_invalid_response")
        return value

    async def test_connection(self) -> None:
        result = await self._json("GET", "/system_stats")
        if not isinstance(result.get("system"), dict) or not isinstance(result.get("devices"), list):
            raise ComfyUiFailure("provider_invalid_response")

    async def submit(self, request: ImageGenerationRequest, prompt_id: str) -> None:
        body = {
            "prompt_id": prompt_id,
            "prompt": self.workflow.build_prompt(request),
            "client_id": "lila-core",
        }
        if len(json.dumps(body, separators=(",", ":")).encode("utf-8")) > MAX_WORKFLOW_JSON_CHARS:
            raise ComfyUiFailure("provider_request_too_large", definitely_rejected=True)
        result = await self._json("POST", "/prompt", body=body)
        returned_id = result.get("prompt_id")
        if returned_id != prompt_id:
            # The remote service may have accepted the job; never submit it again.
            raise ComfyUiFailure("provider_outcome_unknown")

    @staticmethod
    def _queue_ids(value: Any) -> set[str]:
        result: set[str] = set()
        if not isinstance(value, list):
            return result
        for entry in value:
            if isinstance(entry, list) and len(entry) > 1 and isinstance(entry[1], str):
                if re.fullmatch(r"[A-Za-z0-9_-]{1,128}", entry[1]):
                    result.add(entry[1])
        return result

    async def status(self, prompt_id: str) -> tuple[str, list[ComfyUiImageReference]]:
        queue = await self._json("GET", "/queue")
        running = self._queue_ids(queue.get("queue_running"))
        pending = self._queue_ids(queue.get("queue_pending"))
        if prompt_id in running:
            return "running", []
        if prompt_id in pending:
            return "queued", []
        try:
            history = await self._json("GET", "/history/" + quote(prompt_id, safe=""))
        except ComfyUiFailure as failure:
            if failure.status_code == 404:
                return "unknown", []
            raise
        entry = history.get(prompt_id)
        if not isinstance(entry, dict):
            entry = history if "status" in history or "outputs" in history else None
        if not isinstance(entry, dict):
            return "unknown", []
        status = entry.get("status")
        status_text = str(status.get("status_str", "")).lower() if isinstance(status, dict) else ""
        completed = bool(status.get("completed", False)) if isinstance(status, dict) else False
        if status_text in {"error", "failed"}:
            return "failed", []
        if status_text != "success" and not completed:
            return "unknown", []
        images: list[ComfyUiImageReference] = []
        outputs = entry.get("outputs")
        if isinstance(outputs, dict):
            for output in outputs.values():
                if not isinstance(output, dict) or not isinstance(output.get("images"), list):
                    continue
                for raw in output["images"]:
                    if not isinstance(raw, dict):
                        continue
                    try:
                        images.append(ComfyUiImageReference.model_validate({
                            "filename": raw.get("filename"),
                            "subfolder": raw.get("subfolder", ""),
                            "type": raw.get("type", "output"),
                        }))
                    except Exception:
                        continue
        images.sort(key=lambda item: (item.subfolder, item.filename))
        return "completed", images

    async def cancel_queued(self, prompt_id: str) -> bool:
        state, _ = await self.status(prompt_id)
        if state != "queued":
            return False
        await self._request_bytes(
            "POST", "/queue", json_body={"delete": [prompt_id]},
            max_bytes=MAX_COMFYUI_JSON_BYTES,
        )
        state_after, _ = await self.status(prompt_id)
        return state_after == "unknown"

    async def download_result(self, prompt_id: str) -> tuple[bytes, int, int]:
        state, images = await self.status(prompt_id)
        if state != "completed" or not images:
            raise ComfyUiFailure("provider_result_not_ready")
        image = images[0]
        raw, content_type = await self._request_bytes(
            "GET",
            "/view",
            params={
                "filename": image.filename,
                "subfolder": image.subfolder,
                "type": image.type,
            },
            max_bytes=MAX_COMFYUI_IMAGE_BYTES,
        )
        if content_type.split(";", 1)[0].strip().lower() != "image/png":
            raise ComfyUiFailure("provider_unsupported_image")
        if len(raw) < 24 or raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR":
            raise ComfyUiFailure("provider_invalid_image")
        width = int.from_bytes(raw[16:20], "big")
        height = int.from_bytes(raw[20:24], "big")
        if not 1 <= width <= 8192 or not 1 <= height <= 8192:
            raise ComfyUiFailure("provider_invalid_image")
        return raw, width, height


class LilaImageGenerationService:
    """Owner-scoped image orchestration; provider state is snapshotted into each job."""

    def __init__(self, store: SqliteLilaImageStore, client_factory=None):
        self.store = store
        self.client_factory = client_factory or self._default_client_factory

    @staticmethod
    def _default_client_factory(*, timeout_seconds: float, local_allowed_ports):
        return create_provider_http_client(
            "local",
            timeout_seconds=timeout_seconds,
            local_allowed_ports=local_allowed_ports,
        )

    @asynccontextmanager
    async def _adapter(self, config: dict[str, Any]):
        client = self.client_factory(
            timeout_seconds=float(config.get("timeout_seconds", 30.0)),
            local_allowed_ports=COMFYUI_LOCAL_PORTS,
        )
        try:
            yield ComfyUiImageAdapter(config, client)
        finally:
            await client.aclose()

    async def configure_provider(
        self, *, principal_id: str, payload: ComfyUiProviderConfigRequest
    ) -> ImageProviderSummary:
        validate_provider_endpoint(
            "local", payload.base_url, local_allowed_ports=COMFYUI_LOCAL_PORTS
        )
        normalized_base_url = payload.base_url.rstrip("/")
        existing = await self.store.get_provider_config(principal_id=principal_id)
        bearer_token = None
        if payload.bearer_token is not None:
            bearer_token = payload.bearer_token.get_secret_value()
        elif (
            not payload.clear_bearer_token
            and existing is not None
            and existing.get("base_url") == normalized_base_url
        ):
            # Never carry a credential across an endpoint change.
            bearer_token = existing.get("bearer_token")
        config = {
            "base_url": normalized_base_url,
            "bearer_token": bearer_token,
            "timeout_seconds": float(payload.timeout_seconds),
            "workflow": payload.workflow.model_dump(mode="json"),
        }
        await self.store.save_provider_config(principal_id=principal_id, config=config)
        return ImageProviderSummary(
            configured=True,
            workflow_id=payload.workflow.workflow_id,
            has_bearer_token=bool(bearer_token),
        )

    async def provider_summary(self, *, principal_id: str) -> ImageProviderSummary:
        config = await self.store.get_provider_config(principal_id=principal_id)
        if config is None:
            return ImageProviderSummary(configured=False)
        workflow = config.get("workflow")
        workflow_id = workflow.get("workflow_id") if isinstance(workflow, dict) else None
        return ImageProviderSummary(
            configured=True,
            workflow_id=workflow_id,
            has_bearer_token=bool(config.get("bearer_token")),
        )

    async def test_provider(self, *, principal_id: str) -> ImageProviderConnectionTestResponse:
        config = await self.store.get_provider_config(principal_id=principal_id)
        if config is None:
            raise ImageProviderNotConfigured
        async with self._adapter(config) as adapter:
            await adapter.test_connection()
        workflow = config.get("workflow", {})
        return ImageProviderConnectionTestResponse(
            connection_reachable=True,
            workflow_id=str(workflow.get("workflow_id", "")),
        )

    async def submit(
        self, *, principal_id: str, idempotency_key: str, request: ImageGenerationRequest
    ) -> ImageGenerationJobResponse:
        config = await self.store.get_provider_config(principal_id=principal_id)
        if config is None:
            raise ImageProviderNotConfigured
        request_data = request.model_dump(mode="json", exclude={"conversation_id"})
        job, _created = await self.store.create_job(
            principal_id=principal_id,
            conversation_id=request.conversation_id,
            idempotency_key=idempotency_key,
            request=request_data,
            provider_config=config,
        )
        if job["state"] == "prepared":
            await self._submit_prepared(principal_id=principal_id, job=job)
            job = await self.store.get_job(principal_id=principal_id, job_id=job["job_id"])
        return self._response(job)

    async def _submit_prepared(self, *, principal_id: str, job: dict[str, Any]) -> None:
        if not await self.store.claim_submission(principal_id=principal_id, job_id=job["job_id"]):
            return
        try:
            async with self._adapter(job["provider_config"]) as adapter:
                await adapter.submit(
                    ImageGenerationRequest.model_validate({
                        "conversation_id": job["conversation_id"],
                        "prompt": job["prompt"],
                        "negative_prompt": job.get("negative_prompt", ""),
                        "width": job["width"],
                        "height": job["height"],
                        "seed": job.get("seed"),
                    }),
                    str(job["provider_prompt_id"]),
                )
        except asyncio.CancelledError:
            await asyncio.shield(self.store.update_job(
                principal_id=principal_id, job_id=job["job_id"],
                state="submission_unknown", error_code="submission_outcome_unknown",
            ))
            raise
        except ComfyUiFailure as failure:
            await self.store.update_job(
                principal_id=principal_id,
                job_id=job["job_id"],
                state="failed" if failure.definitely_rejected else "submission_unknown",
                error_code=failure.code,
            )
        except Exception:
            await self.store.update_job(
                principal_id=principal_id, job_id=job["job_id"],
                state="submission_unknown", error_code="submission_outcome_unknown",
            )
        else:
            await self.store.update_job(
                principal_id=principal_id, job_id=job["job_id"], state="queued"
            )

    async def get_job(self, *, principal_id: str, job_id: str) -> ImageGenerationJobResponse:
        job = await self.store.get_job(principal_id=principal_id, job_id=job_id)
        if job is None:
            raise ImageJobNotFound
        if job["state"] == "prepared":
            await self._submit_prepared(principal_id=principal_id, job=job)
            job = await self.store.get_job(principal_id=principal_id, job_id=job_id)
        elif job["state"] in {
            "submitting", "submission_unknown", "queued", "running",
            "provider_completed", "result_pending",
        }:
            job = await self._refresh_job(principal_id=principal_id, job=job)
        return self._response(job)

    async def _refresh_job(self, *, principal_id: str, job: dict[str, Any]) -> dict[str, Any]:
        if job["state"] in {"provider_completed", "result_pending"}:
            state = "completed"
            images_ready = True
        else:
            state = "unknown"
            images_ready = False
            images = []
            try:
                async with self._adapter(job["provider_config"]) as adapter:
                    state, images = await adapter.status(str(job["provider_prompt_id"]))
            except Exception:
                return job
            if state == "completed" and not images:
                return await self.store.update_job(
                    principal_id=principal_id, job_id=job["job_id"],
                    state="failed", error_code="provider_result_missing",
                )

        if state in {"queued", "running"}:
            return await self.store.update_job(
                principal_id=principal_id, job_id=job["job_id"], state=state
            )
        if state == "failed":
            return await self.store.update_job(
                principal_id=principal_id, job_id=job["job_id"],
                state="failed", error_code="provider_generation_failed",
            )
        if state == "unknown":
            if job["state"] == "submitting":
                return await self.store.update_job(
                    principal_id=principal_id, job_id=job["job_id"],
                    state="submission_unknown", error_code="submission_outcome_unknown",
                )
            return job
        if state == "completed" or images_ready:
            current = job
            if current["state"] != "provider_completed" and current["state"] != "result_pending":
                current = await self.store.update_job(
                    principal_id=principal_id, job_id=job["job_id"], state="provider_completed"
                )
            try:
                async with self._adapter(current["provider_config"]) as adapter:
                    image_bytes, width, height = await adapter.download_result(
                        str(current["provider_prompt_id"])
                    )
                await self.store.save_asset(
                    principal_id=principal_id,
                    job_id=current["job_id"],
                    mime_type="image/png",
                    width=width,
                    height=height,
                    image_bytes=image_bytes,
                )
                return await self.store.get_job(
                    principal_id=principal_id, job_id=current["job_id"]
                )
            except ComfyUiFailure as failure:
                if failure.code in {
                    "provider_unsupported_image",
                    "provider_invalid_image",
                    "provider_invalid_response",
                    "provider_response_too_large",
                    "provider_request_too_large",
                    "redirect_not_allowed",
                    "provider_rejected",
                }:
                    return await self.store.update_job(
                        principal_id=principal_id, job_id=current["job_id"],
                        state="failed", error_code=failure.code,
                    )
                if current["state"] != "result_pending":
                    return await self.store.update_job(
                        principal_id=principal_id, job_id=current["job_id"],
                        state="result_pending", error_code="result_download_pending",
                    )
                return current
            except Exception:
                if current["state"] != "result_pending":
                    return await self.store.update_job(
                        principal_id=principal_id, job_id=current["job_id"],
                        state="result_pending", error_code="result_download_pending",
                    )
                return current
        return job

    async def cancel(self, *, principal_id: str, job_id: str) -> ImageGenerationJobResponse:
        job = await self.store.get_job(principal_id=principal_id, job_id=job_id)
        if job is None:
            raise ImageJobNotFound
        if job["state"] != "queued":
            raise ImageJobNotCancellable
        try:
            async with self._adapter(job["provider_config"]) as adapter:
                cancelled = await adapter.cancel_queued(str(job["provider_prompt_id"]))
        except Exception:
            raise ImageJobNotCancellable from None
        if not cancelled:
            refreshed = await self.get_job(principal_id=principal_id, job_id=job_id)
            if refreshed.state != "cancelled":
                raise ImageJobNotCancellable
            return refreshed
        job = await self.store.update_job(
            principal_id=principal_id, job_id=job_id, state="cancelled"
        )
        return self._response(job)

    async def history(
        self, *, principal_id: str, conversation_id: str, limit: int = 50
    ) -> ImageGenerationHistoryResponse:
        jobs = await self.store.list_jobs(
            principal_id=principal_id, conversation_id=conversation_id, limit=limit
        )
        return ImageGenerationHistoryResponse(
            conversation_id=conversation_id,
            jobs=[self._response(job) for job in jobs],
        )

    async def asset(self, *, principal_id: str, job_id: str) -> dict[str, Any] | None:
        return await self.store.get_asset(principal_id=principal_id, job_id=job_id)

    @staticmethod
    def _response(job: dict[str, Any] | None) -> ImageGenerationJobResponse:
        if job is None:
            raise ImageJobNotFound
        return ImageGenerationJobResponse(
            job_id=str(job["job_id"]),
            conversation_id=str(job["conversation_id"]),
            prompt=str(job["prompt"]),
            negative_prompt=str(job.get("negative_prompt", "")),
            width=int(job["width"]),
            height=int(job["height"]),
            seed=job.get("seed"),
            state=job["state"],
            created_at_epoch_millis=int(job["created_at_epoch_millis"]),
            updated_at_epoch_millis=int(job["updated_at_epoch_millis"]),
            asset=job.get("asset"),
            error_code=job.get("error_code"),
        )
