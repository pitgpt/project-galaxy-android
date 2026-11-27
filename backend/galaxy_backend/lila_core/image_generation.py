"""Provider-neutral Lila image generation with a bounded local ComfyUI adapter."""
from __future__ import annotations

import asyncio
import hashlib
import json
import re
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
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
MAX_COMFYUI_CHECKPOINTS = 256
MAX_COMFYUI_CHECKPOINT_NAME_CHARS = 512
IMAGE_PROVIDER_RECOVERY_GRACE_SECONDS = 10 * 60
COMFYUI_LOCAL_PORTS = frozenset({8188})


def image_seed_for_idempotency_key(idempotency_key: str) -> int:
    """Return the shared unsigned 32-bit SHA-256 seed for an image request key."""
    if not isinstance(idempotency_key, str) or len(idempotency_key) > 128:
        raise ValueError("Image idempotency key is invalid")
    digest = hashlib.sha256(idempotency_key.encode("utf-8")).digest()
    return int.from_bytes(digest[:4], byteorder="big", signed=False)


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
        if self.workflow_id == "lila.sdxl.v1":
            self._validate_builtin_sdxl_graph(graph)
        return self

    def _validate_builtin_sdxl_graph(self, graph: dict[str, Any]) -> None:
        """Fail closed when a caller labels a custom graph as the built-in SDXL preset."""
        expected_nodes = {"3", "4", "5", "6", "7", "8", "9"}
        if set(graph) != expected_nodes:
            raise ValueError("built-in SDXL workflow has an unexpected node set")

        def inputs(node_id: str, class_type: str, expected_inputs: set[str]) -> dict[str, Any]:
            node = graph.get(node_id)
            if (
                not isinstance(node, dict)
                or set(node) != {"class_type", "inputs"}
                or node.get("class_type") != class_type
                or not isinstance(node.get("inputs"), dict)
                or set(node["inputs"]) != expected_inputs
            ):
                raise ValueError("built-in SDXL workflow contains an invalid node")
            return node["inputs"]

        def link(values: dict[str, Any], name: str, source: str, output: int) -> None:
            value = values.get(name)
            if (
                not isinstance(value, list)
                or len(value) != 2
                or value[0] != source
                or type(value[1]) is not int
                or value[1] != output
            ):
                raise ValueError("built-in SDXL workflow contains an invalid connection")

        checkpoint = inputs("4", "CheckpointLoaderSimple", {"ckpt_name"})
        checkpoint_name = checkpoint["ckpt_name"]
        if (
            not isinstance(checkpoint_name, str)
            or not checkpoint_name
            or checkpoint_name != checkpoint_name.strip()
            or len(checkpoint_name) > MAX_COMFYUI_CHECKPOINT_NAME_CHARS
            or checkpoint_name.startswith("/")
            or "\\" in checkpoint_name
            or ":" in checkpoint_name
            or any(ord(character) < 32 or ord(character) == 127 for character in checkpoint_name)
            or any(part in {"", ".", ".."} for part in checkpoint_name.split("/"))
        ):
            raise ValueError("built-in SDXL workflow has an unsafe checkpoint name")

        positive = inputs("5", "CLIPTextEncode", {"text", "clip"})
        negative = inputs("6", "CLIPTextEncode", {"text", "clip"})
        if (
            not isinstance(positive["text"], str)
            or len(positive["text"]) > 12_000
            or not isinstance(negative["text"], str)
            or len(negative["text"]) > 4_000
        ):
            raise ValueError("built-in SDXL workflow prompt inputs are invalid")
        link(positive, "clip", "4", 1)
        link(negative, "clip", "4", 1)

        latent = inputs("7", "EmptyLatentImage", {"width", "height", "batch_size"})
        if (
            type(latent["width"]) is not int
            or latent["width"] != 1024
            or type(latent["height"]) is not int
            or latent["height"] != 1024
            or type(latent["batch_size"]) is not int
            or latent["batch_size"] != 1
        ):
            raise ValueError("built-in SDXL workflow dimensions are invalid")

        sampler = inputs(
            "3",
            "KSampler",
            {
                "seed", "steps", "cfg", "sampler_name", "scheduler", "denoise",
                "model", "positive", "negative", "latent_image",
            },
        )
        if (
            type(sampler["seed"]) is not int
            or sampler["seed"] != 0
            or type(sampler["steps"]) is not int
            or sampler["steps"] != 30
            or type(sampler["cfg"]) not in {int, float}
            or sampler["cfg"] != 5.5
            or sampler["sampler_name"] != "dpmpp_2m"
            or sampler["scheduler"] != "karras"
            or type(sampler["denoise"]) not in {int, float}
            or sampler["denoise"] != 1.0
        ):
            raise ValueError("built-in SDXL workflow sampler settings are invalid")
        link(sampler, "model", "4", 0)
        link(sampler, "positive", "5", 0)
        link(sampler, "negative", "6", 0)
        link(sampler, "latent_image", "7", 0)

        decoded = inputs("8", "VAEDecode", {"samples", "vae"})
        link(decoded, "samples", "3", 0)
        link(decoded, "vae", "4", 2)

        saved = inputs("9", "SaveImage", {"images", "filename_prefix"})
        link(saved, "images", "8", 0)
        if saved["filename_prefix"] != "lila":
            raise ValueError("built-in SDXL workflow output prefix is invalid")

        expected_mappings = (
            (self.positive_prompt, "5", "text"),
            (self.negative_prompt, "6", "text"),
            (self.width, "7", "width"),
            (self.height, "7", "height"),
            (self.seed, "3", "seed"),
        )
        if any(
            mapping is None or (mapping.node_id, mapping.input_name) != (node_id, input_name)
            for mapping, node_id, input_name in expected_mappings
        ):
            raise ValueError("built-in SDXL workflow mappings are invalid")

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

class ImageProviderConfigurationResponse(BaseModel):
    """Owner-authenticated editable configuration; bearer credentials are never serialized."""

    model_config = ConfigDict(extra="forbid")

    provider_id: Literal["comfyui"] = "comfyui"
    configured: bool
    base_url: str | None = Field(default=None, max_length=2048)
    timeout_seconds: float | None = Field(default=None, ge=1.0, le=30.0)
    workflow: ComfyUiWorkflowConfig | None = None
    has_bearer_token: bool = False
    supports_queued_cancellation: bool = True
    supports_running_cancellation: Literal[False] = False



class ImageProviderConnectionTestResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    provider_id: Literal["comfyui"] = "comfyui"
    connection_reachable: bool
    workflow_id: str
    checkpoint_discovery_supported: bool = False
    available_checkpoints: list[str] = Field(default_factory=list, max_length=MAX_COMFYUI_CHECKPOINTS)
    checkpoint_list_truncated: bool = False


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
    next_cursor: str | None = Field(default=None, max_length=256)


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


@dataclass(frozen=True)
class ImageProviderStatus:
    state: Literal["unknown", "queued", "running", "completed", "failed"]
    result_available: bool = False


class ImageProviderAdapter(Protocol):
    """Provider-neutral image lifecycle contract used by Lila Core."""

    async def test_connection(self) -> None: ...
    async def submit(self, request: ImageGenerationRequest, prompt_id: str) -> None: ...
    async def status(self, prompt_id: str) -> ImageProviderStatus: ...
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

    async def discover_checkpoints(self) -> tuple[bool, list[str], bool]:
        try:
            info = await self._json("GET", "/object_info/CheckpointLoaderSimple")
        except ComfyUiFailure as failure:
            if failure.status_code in {404, 405}:
                return False, [], False
            raise
        node = info.get("CheckpointLoaderSimple")
        node_inputs = node.get("input") if isinstance(node, dict) else None
        required = node_inputs.get("required") if isinstance(node_inputs, dict) else None
        checkpoint_spec = required.get("ckpt_name") if isinstance(required, dict) else None
        if not isinstance(checkpoint_spec, list) or not checkpoint_spec:
            return False, [], False
        choices = checkpoint_spec[0]
        if not isinstance(choices, list):
            return False, [], False
        names: list[str] = []
        seen: set[str] = set()
        for raw_name in choices:
            if not isinstance(raw_name, str):
                continue
            name = raw_name.strip()
            if (
                not name
                or len(name) > MAX_COMFYUI_CHECKPOINT_NAME_CHARS
                or name.startswith("/")
                or "\\" in name
                or any(ord(character) < 32 or ord(character) == 127 for character in name)
            ):
                continue
            segments = name.split("/")
            if any(segment in {"", ".", ".."} for segment in segments):
                continue
            if name not in seen:
                seen.add(name)
                names.append(name)
        return True, names[:MAX_COMFYUI_CHECKPOINTS], len(names) > MAX_COMFYUI_CHECKPOINTS

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

    async def status(self, prompt_id: str) -> ImageProviderStatus:
        state, images = await self._status_with_images(prompt_id)
        return ImageProviderStatus(state=state, result_available=bool(images))

    async def _status_with_images(self, prompt_id: str) -> tuple[str, list[ComfyUiImageReference]]:
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
        state, _ = await self._status_with_images(prompt_id)
        if state != "queued":
            return False
        await self._request_bytes(
            "POST", "/queue", json_body={"delete": [prompt_id]},
            max_bytes=MAX_COMFYUI_JSON_BYTES,
        )
        state_after, _ = await self._status_with_images(prompt_id)
        return state_after == "unknown"

    async def download_result(self, prompt_id: str) -> tuple[bytes, int, int]:
        state, images = await self._status_with_images(prompt_id)
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

    def __init__(self, store: SqliteLilaImageStore, client_factory=None, clock_ms=None):
        self.store = store
        self.client_factory = client_factory or self._default_client_factory
        self.clock_ms = clock_ms or (lambda: int(time.time() * 1000))

    def _recovery_grace_expired(self, job: dict[str, Any]) -> bool:
        updated_at = job.get("updated_at_epoch_millis")
        if not isinstance(updated_at, int) or isinstance(updated_at, bool) or updated_at <= 0:
            return False
        return self.clock_ms() - updated_at >= IMAGE_PROVIDER_RECOVERY_GRACE_SECONDS * 1000

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

    async def provider_configuration(
        self, *, principal_id: str
    ) -> ImageProviderConfigurationResponse:
        config = await self.store.get_provider_config(principal_id=principal_id)
        if config is None:
            return ImageProviderConfigurationResponse(configured=False)
        workflow = ComfyUiWorkflowConfig.model_validate(config.get("workflow"))
        return ImageProviderConfigurationResponse(
            configured=True,
            base_url=str(config["base_url"]),
            timeout_seconds=float(config.get("timeout_seconds", 30.0)),
            workflow=workflow,
            has_bearer_token=bool(config.get("bearer_token")),
        )

    async def test_provider(self, *, principal_id: str) -> ImageProviderConnectionTestResponse:
        config = await self.store.get_provider_config(principal_id=principal_id)
        if config is None:
            raise ImageProviderNotConfigured
        async with self._adapter(config) as adapter:
            await adapter.test_connection()
            discovery_supported, checkpoints, truncated = await adapter.discover_checkpoints()
        workflow = config.get("workflow", {})
        return ImageProviderConnectionTestResponse(
            connection_reachable=True,
            workflow_id=str(workflow.get("workflow_id", "")),
            checkpoint_discovery_supported=discovery_supported,
            available_checkpoints=checkpoints,
            checkpoint_list_truncated=truncated,
        )

    async def submit(
        self, *, principal_id: str, idempotency_key: str, request: ImageGenerationRequest
    ) -> ImageGenerationJobResponse:
        config = await self.store.get_provider_config(principal_id=principal_id)
        if config is None:
            raise ImageProviderNotConfigured
        if request.seed is None:
            request = request.model_copy(
                update={"seed": image_seed_for_idempotency_key(idempotency_key)}
            )
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
                    provider_status = await adapter.status(str(job["provider_prompt_id"]))
                    state = provider_status.state
            except Exception:
                return job
            if state == "completed" and not provider_status.result_available:
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
            if job["state"] in {"queued", "running"} and self._recovery_grace_expired(job):
                # A confirmed provider job that disappears from both queue and history
                # cannot be safely resubmitted. End the stale job instead of polling forever.
                return await self.store.update_job(
                    principal_id=principal_id, job_id=job["job_id"],
                    state="failed", error_code="provider_job_missing",
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
                if current["state"] == "result_pending" and self._recovery_grace_expired(current):
                    return await self.store.update_job(
                        principal_id=principal_id, job_id=current["job_id"],
                        state="failed", error_code="provider_result_unavailable",
                    )
                if current["state"] != "result_pending":
                    return await self.store.update_job(
                        principal_id=principal_id, job_id=current["job_id"],
                        state="result_pending", error_code="result_download_pending",
                    )
                return current
            except Exception:
                if current["state"] == "result_pending" and self._recovery_grace_expired(current):
                    return await self.store.update_job(
                        principal_id=principal_id, job_id=current["job_id"],
                        state="failed", error_code="result_persistence_failed",
                    )
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
        self, *, principal_id: str, conversation_id: str, limit: int = 50,
        cursor: str | None = None,
    ) -> ImageGenerationHistoryResponse:
        jobs, next_cursor = await self.store.list_jobs_page(
            principal_id=principal_id,
            conversation_id=conversation_id,
            limit=limit,
            cursor=cursor,
        )
        return ImageGenerationHistoryResponse(
            conversation_id=conversation_id,
            jobs=[self._response(job) for job in jobs],
            next_cursor=next_cursor,
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
