"""Tests for the `/models` auto-fetch logic.

These run offline: the pure helpers are exercised directly, and the HTTP path is driven with
a stubbed httpx transport so the error classification is verified without network access.
"""

from __future__ import annotations

import httpx
import pytest
from fastapi.testclient import TestClient

from app import models_api
from app.main import app


@pytest.fixture(scope="module")
def client() -> TestClient:
    return TestClient(app)


def test_build_endpoint_accepts_common_shapes() -> None:
    assert models_api._build_endpoint("https://x/v1") == "https://x/v1/models"
    assert models_api._build_endpoint("https://x/v1/") == "https://x/v1/models"
    assert models_api._build_endpoint("https://x/v1/models") == "https://x/v1/models"
    assert models_api._build_endpoint("  https://x  ") == "https://x/models"
    assert models_api._build_endpoint("ftp://x") is None
    assert models_api._build_endpoint("") is None


def test_parse_openai_payload_and_free_tagging() -> None:
    payload = {
        "data": [
            {"id": "z-model"},
            {"id": "free-model:free"},
            {"id": "paid-model", "pricing": {"prompt": "0.000001", "completion": "0.000002"}},
            {"id": "zero-priced", "pricing": {"prompt": "0", "completion": "0"}},
            {"id": "z-model"},  # duplicate
            "plain-string-model",
        ],
    }
    models = {item.id: item for item in models_api._parse_openai_payload(payload)}

    assert set(models) == {
        "z-model",
        "free-model:free",
        "paid-model",
        "zero-priced",
        "plain-string-model",
    }
    assert models["free-model:free"].looks_free is True
    assert models["zero-priced"].looks_free is True
    assert models["paid-model"].looks_free is False
    assert models["z-model"].looks_free is False


def test_parse_openai_payload_rejects_non_list() -> None:
    assert models_api._parse_openai_payload({"data": "nope"}) == []
    assert models_api._parse_openai_payload("not a dict") == []


def test_bad_url_is_reported_not_raised(client: TestClient) -> None:
    response = client.post(
        "/v1/models/list",
        json={"provider": "OPENAI_COMPATIBLE", "base_url": "localhost:1234", "api_key": "k"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["ok"] is False
    assert body["error_kind"] == "bad_url"


def test_unauthorized_is_classified(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    class _Unauthorized(httpx.AsyncBaseTransport):
        async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
            return httpx.Response(401, json={"error": "bad key"})

    _patch_transport(monkeypatch, _Unauthorized())

    body = client.post(
        "/v1/models/list",
        json={"base_url": "https://example.test/v1", "api_key": "wrong"},
    ).json()
    assert body["ok"] is False
    assert body["error_kind"] == "unauthorized"


def test_empty_list_is_classified(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    class _Empty(httpx.AsyncBaseTransport):
        async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json={"data": []})

    _patch_transport(monkeypatch, _Empty())

    body = client.post(
        "/v1/models/list",
        json={"base_url": "https://example.test/v1", "api_key": "k"},
    ).json()
    assert body["ok"] is False
    assert body["error_kind"] == "empty"


def test_timeout_is_classified(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    class _Timeout(httpx.AsyncBaseTransport):
        async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
            raise httpx.ReadTimeout("too slow", request=request)

    _patch_transport(monkeypatch, _Timeout())

    body = client.post(
        "/v1/models/list",
        json={"base_url": "https://example.test/v1", "api_key": "k"},
    ).json()
    assert body["ok"] is False
    assert body["error_kind"] == "timeout"


def test_happy_path_returns_sorted_models(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    class _Ok(httpx.AsyncBaseTransport):
        async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
            assert request.url.path.endswith("/models")
            assert request.headers.get("authorization") == "Bearer good"
            return httpx.Response(
                200,
                json={"data": [{"id": "b-model"}, {"id": "a-model:free"}]},
            )

    _patch_transport(monkeypatch, _Ok())

    body = client.post(
        "/v1/models/list",
        json={"base_url": "https://example.test/v1", "api_key": "good"},
    ).json()
    assert body["ok"] is True
    assert [item["id"] for item in body["models"]] == ["a-model:free", "b-model"]
    assert body["models"][0]["looks_free"] is True


def _patch_transport(monkeypatch: pytest.MonkeyPatch, transport: httpx.AsyncBaseTransport) -> None:
    """Route every httpx.AsyncClient built inside models_api through a stub transport."""

    original = httpx.AsyncClient

    def factory(*args: object, **kwargs: object) -> httpx.AsyncClient:
        kwargs.pop("timeout", None)
        return original(transport=transport, timeout=1.0)  # type: ignore[arg-type]

    monkeypatch.setattr(models_api.httpx, "AsyncClient", factory)
