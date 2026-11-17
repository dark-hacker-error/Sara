"""`/models` auto-fetch.

Two paths, both exposed through one endpoint:

- **OPENAI_COMPATIBLE**: `GET {base_url}/models` with a bearer token. This is the OpenAI REST
  contract, so it covers OpenRouter, Together, Groq, Fireworks, DeepInfra, LM Studio, vLLM,
  Ollama's OpenAI shim, and anything else that speaks it.
- **GEMINI_NATIVE**: `client.models.list()` from google-genai.

Errors are classified rather than collapsed into a 500, because the app needs to tell the
user *which* thing is wrong (bad key vs. bad URL vs. nothing listed).
"""

from __future__ import annotations

import logging
from typing import Any, Literal

import httpx
from fastapi import APIRouter
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/v1/models", tags=["models"])

Provider = Literal["OPENAI_COMPATIBLE", "GEMINI_NATIVE"]

REQUEST_TIMEOUT_SECONDS = 15.0


class ModelListRequest(BaseModel):
    provider: Provider = "OPENAI_COMPATIBLE"
    base_url: str = ""
    api_key: str = ""


class ModelItem(BaseModel):
    id: str
    looks_free: bool = False


class ModelListResponse(BaseModel):
    ok: bool
    models: list[ModelItem] = Field(default_factory=list)
    error_kind: str | None = None
    error: str | None = None


def _build_endpoint(base_url: str) -> str | None:
    """Normalise the base URL. Accepts `.../v1`, `.../v1/`, `.../v1/models` or a bare host."""
    trimmed = base_url.strip().rstrip("/")
    if not trimmed.startswith(("http://", "https://")):
        return None
    return trimmed if trimmed.endswith("/models") else f"{trimmed}/models"


def _parse_openai_payload(payload: Any) -> list[ModelItem]:
    if not isinstance(payload, dict):
        return []
    entries = payload.get("data")
    if entries is None:
        entries = payload.get("models")
    if not isinstance(entries, list):
        return []

    models: list[ModelItem] = []
    seen: set[str] = set()
    for entry in entries:
        if isinstance(entry, dict):
            model_id = entry.get("id") or entry.get("name") or ""
            pricing = entry.get("pricing") if isinstance(entry.get("pricing"), dict) else {}
        elif isinstance(entry, str):
            model_id, pricing = entry, {}
        else:
            continue

        model_id = str(model_id).strip()
        if not model_id or model_id in seen:
            continue
        seen.add(model_id)
        models.append(ModelItem(id=model_id, looks_free=_looks_free(model_id, pricing)))

    models.sort(key=lambda item: item.id)
    return models


def _looks_free(model_id: str, pricing: dict[str, Any]) -> bool:
    """Heuristic "free" tag. A label for the dropdown, never a guarantee.

    - OpenRouter marks free variants with a `:free` suffix.
    - Several providers append `-free` or prefix `free`.
    - OpenRouter also publishes `pricing`; a zero prompt *and* completion price is strong
      evidence, so it wins when present.
    """
    lower = model_id.lower()
    suffix_free = ":free" in lower or lower.endswith("-free") or lower.startswith("free")

    prompt = pricing.get("prompt")
    completion = pricing.get("completion")
    if prompt is not None and completion is not None:
        try:
            if float(prompt) == 0.0 and float(completion) == 0.0:
                return True
        except (TypeError, ValueError):
            pass

    return suffix_free


@router.post("/list", response_model=ModelListResponse)
async def list_models(request: ModelListRequest) -> ModelListResponse:
    """Fetch the model catalogue for a provider configuration."""
    if request.provider == "GEMINI_NATIVE":
        return await _list_gemini(request.api_key)
    return await _list_openai_compatible(request.base_url, request.api_key)


async def _list_openai_compatible(base_url: str, api_key: str) -> ModelListResponse:
    endpoint = _build_endpoint(base_url)
    if endpoint is None:
        return ModelListResponse(
            ok=False,
            error_kind="bad_url",
            error=f"'{base_url}' is not an http(s) base URL.",
        )

    headers = {"Accept": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    try:
        async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT_SECONDS) as client:
            response = await client.get(endpoint, headers=headers)
    except httpx.TimeoutException:
        return ModelListResponse(
            ok=False,
            error_kind="timeout",
            error=f"Timed out after {REQUEST_TIMEOUT_SECONDS:.0f}s calling {endpoint}.",
        )
    except httpx.HTTPError as exc:
        return ModelListResponse(
            ok=False,
            error_kind="network",
            error=f"Network error reaching {endpoint}: {exc}",
        )

    if response.status_code in (401, 403):
        return ModelListResponse(
            ok=False,
            error_kind="unauthorized",
            error=f"The provider rejected this API key (HTTP {response.status_code}).",
        )
    if response.status_code >= 400:
        return ModelListResponse(
            ok=False,
            error_kind="server",
            error=f"Provider returned HTTP {response.status_code}: {response.text[:200]}",
        )

    try:
        payload = response.json()
    except ValueError:
        return ModelListResponse(
            ok=False,
            error_kind="malformed",
            error=f"Response was not JSON. Got: {response.text[:200]}",
        )

    models = _parse_openai_payload(payload)
    if not models:
        return ModelListResponse(
            ok=False,
            error_kind="empty",
            error=(
                "The endpoint answered but listed no models. Check the base URL points at the "
                "API root that serves /models."
            ),
        )

    logger.info("Fetched %d models from %s", len(models), endpoint)
    return ModelListResponse(ok=True, models=models)


async def _list_gemini(api_key: str) -> ModelListResponse:
    if not api_key:
        import os

        api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        return ModelListResponse(
            ok=False,
            error_kind="unauthorized",
            error="No Gemini API key provided and GEMINI_API_KEY is not set.",
        )

    try:
        from google import genai
    except ImportError as exc:  # pragma: no cover - google-genai is a hard dependency
        return ModelListResponse(
            ok=False,
            error_kind="server",
            error=f"google-genai is not installed: {exc}",
        )

    try:
        import asyncio

        client = genai.Client(api_key=api_key)

        def _collect() -> list[ModelItem]:
            items: list[ModelItem] = []
            for model in client.models.list(config={"page_size": 200}):
                name = str(getattr(model, "name", "") or "")
                if not name:
                    continue
                items.append(ModelItem(id=name.removeprefix("models/")))
            items.sort(key=lambda item: item.id)
            return items

        # The SDK call is synchronous; keep the event loop free.
        models = await asyncio.to_thread(_collect)
    except Exception as exc:  # noqa: BLE001 - surfaced to the caller as a message
        return ModelListResponse(ok=False, error_kind="server", error=str(exc))

    if not models:
        return ModelListResponse(
            ok=False,
            error_kind="empty",
            error="Gemini returned an empty model list.",
        )
    return ModelListResponse(ok=True, models=models)
