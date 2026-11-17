"""Tests for the WebSocket session and the agent factory.

Scope is deliberately "does the wiring hold": handshake, config push, error frames, and that
the agent tree can be constructed from a config snapshot. Nothing here calls a model, so it
runs offline and fast.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.agents import build_root_agent
from app.config import (
    ORCHESTRATOR_NAME,
    AgentSpec,
    AppConfig,
    parse_agents,
)
from app.device import DeviceBridge
from app.main import app, store


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


def _agent(name: str, role: str, *, model: str = "some-model") -> AgentSpec:
    return AgentSpec(
        id=name,
        name=name,
        role=role,
        enabled=True,
        provider="OPENAI_COMPATIBLE",
        base_url="https://provider.test/v1",
        api_key="test-key",
        model=model,
    )


def test_health_reports_config(client: TestClient) -> None:
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["protocol"] == 1
    assert isinstance(body["subagents"], list)


def test_websocket_handshake_sends_ready(client: TestClient) -> None:
    with client.websocket_connect("/ws/session") as ws:
        frame = ws.receive_json()
        assert frame["type"] == "ready"
        assert frame["protocol"] == 1
        assert isinstance(frame["agents"], list)


def test_hello_pushes_agent_config_and_updates_ready(client: TestClient) -> None:
    with client.websocket_connect("/ws/session") as ws:
        ws.receive_json()  # initial ready

        ws.send_json(
            {
                "type": "hello",
                "protocol": 1,
                "live_model": "gemini-3.8-live",
                "agents": [
                    {
                        "id": "p1",
                        "name": "phone_control_agent",
                        "role": "PHONE_CONTROL",
                        "enabled": True,
                        "provider": "OPENAI_COMPATIBLE",
                        "base_url": "https://provider.test/v1",
                        "api_key": "k",
                        "model": "vision-model",
                        "system_prompt": "",
                    },
                    {
                        # Not runnable: no model. Must be excluded, not crash.
                        "id": "r1",
                        "name": "research_agent",
                        "role": "RESEARCH",
                        "enabled": True,
                        "provider": "OPENAI_COMPATIBLE",
                        "base_url": "https://provider.test/v1",
                        "api_key": "k",
                        "model": "",
                        "system_prompt": "",
                    },
                ],
            },
        )

        ready = ws.receive_json()
        assert ready["type"] == "ready"
        assert ready["agents"] == ["phone_control_agent"]
        assert ready["live_model"] == "gemini-3.8-live"
        assert ready["config_generation"] >= 1


def test_parse_agents_skips_malformed_entries() -> None:
    specs = parse_agents(
        [
            {"name": "ok", "role": "RESEARCH", "model": "m"},
            "not-a-dict",
            {"role": "RESEARCH"},  # missing name
        ],
    )
    assert [spec.name for spec in specs] == ["ok"]


def test_delegate_without_orchestrator_returns_error(client: TestClient) -> None:
    original = store.snapshot()
    try:
        store.replace(
            AppConfig(
                orchestrator=AgentSpec(name=ORCHESTRATOR_NAME, provider="GEMINI_NATIVE", model=""),
                agents=[],
                live_model="gemini-3.8-live",
            ),
        )
        with client.websocket_connect("/ws/session") as ws:
            ws.receive_json()
            ws.send_json({"type": "delegate", "task_id": "t1", "goal": "open whatsapp"})
            frame = ws.receive_json()
            assert frame["type"] == "error"
            assert frame["task_id"] == "t1"
    finally:
        store.replace(original)


def test_unknown_frame_type_returns_error(client: TestClient) -> None:
    with client.websocket_connect("/ws/session") as ws:
        ws.receive_json()
        ws.send_json({"type": "nonsense"})
        frame = ws.receive_json()
        assert frame["type"] == "error"


@pytest.mark.asyncio
async def test_factory_builds_tree_with_subagents() -> None:
    """The tree must assemble without contacting any provider."""
    config = AppConfig(
        orchestrator=AgentSpec(
            name=ORCHESTRATOR_NAME,
            provider="OPENAI_COMPATIBLE",
            base_url="https://orchestrator.test/v1",
            api_key="k",
            model="planner-model",
        ),
        agents=[
            _agent("phone_control_agent", "phone_control"),
            _agent("research_agent", "research"),
            _agent("extra_agent", "custom"),
            _agent("disabled_agent", "research", model=""),  # unrunnable, must be skipped
        ],
    )

    async def _send(_frame: dict) -> None:  # pragma: no cover - never invoked here
        return None

    root = build_root_agent(config, DeviceBridge(_send, "task"))

    assert root.name == ORCHESTRATOR_NAME
    names = [getattr(agent, "name", "") for agent in root.sub_agents]
    assert names == ["phone_control_agent", "research_agent", "extra_agent"]
    assert "disabled_agent" not in names

    phone = root.sub_agents[0]
    # Device tools are attached, and every one of them is a callable ADK can wrap.
    assert len(phone.tools) >= 10


@pytest.mark.asyncio
async def test_factory_handles_no_runnable_subagents() -> None:
    config = AppConfig(
        orchestrator=AgentSpec(
            name=ORCHESTRATOR_NAME,
            provider="OPENAI_COMPATIBLE",
            base_url="https://orchestrator.test/v1",
            api_key="k",
            model="planner-model",
        ),
        agents=[_agent("research_agent", "research", model="")],
    )

    async def _send(_frame: dict) -> None:  # pragma: no cover
        return None

    root = build_root_agent(config, DeviceBridge(_send, "task"))
    assert root.sub_agents == []
    assert "no subagents" in root.instruction
