"""Per-connection orchestration.

Flow for one delegated goal:

    app --delegate--> here --run_async--> sara_orchestrator
                                              |
                                              +-- transfer -> phone_control_agent
                                              |                  |
                                              |           device tool call
                                              |                  v
                                              +<-- device_action frame --> app
                                              |
                                        delegate_result

One ADK `Runner` per delegation, built fresh from the current config snapshot. Each
delegation gets its own `InMemorySessionService` keyed by the task id, so two concurrent
tasks cannot see each other's history.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

from fastapi import WebSocket, WebSocketDisconnect
from google.adk.agents.run_config import RunConfig
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai import types

from . import protocol
from .agents import build_root_agent
from .device import DeviceBridge
from .store import ConfigStore

logger = logging.getLogger(__name__)

APP_NAME = "sara"
USER_ID = "device"

# Hard ceiling on model calls per delegation. Without it a confused planner can loop
# indefinitely on a phone that keeps returning the same screen.
MAX_LLM_CALLS = 25


class SessionHandler:
    def __init__(self, websocket: WebSocket, store: ConfigStore) -> None:
        self._websocket = websocket
        self._store = store
        self._send_lock = asyncio.Lock()
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._bridges: dict[str, DeviceBridge] = {}

    # ------------------------------------------------------------------ lifecycle

    async def run(self) -> None:
        config = self._store.snapshot()
        await self._send(
            protocol.ready(
                agents=self._store.runnable_subagents(),
                live_model=config.live_model,
                config_generation=self._store.generation,
            ),
        )

        try:
            while True:
                raw = await self._websocket.receive_text()
                await self._handle_frame(raw)
        except WebSocketDisconnect:
            logger.info("Phone disconnected")
        except Exception:  # noqa: BLE001 - a broken frame must not leak tasks
            logger.exception("Session loop failed")
        finally:
            await self._shutdown()

    async def _shutdown(self) -> None:
        for task in self._tasks.values():
            task.cancel()
        for bridge in self._bridges.values():
            bridge.close()
        self._tasks.clear()
        self._bridges.clear()

    # ------------------------------------------------------------------ frames in

    async def _handle_frame(self, raw: str) -> None:
        try:
            frame = json.loads(raw)
        except json.JSONDecodeError:
            await self._send(protocol.error("Frames must be JSON objects."))
            return

        if not isinstance(frame, dict):
            await self._send(protocol.error("Frames must be JSON objects."))
            return

        frame_type = frame.get("type")

        if frame_type in ("hello", "config_update"):
            await self._on_config(frame, announce=frame_type == "hello")
        elif frame_type == "delegate":
            await self._on_delegate(frame)
        elif frame_type == "device_result":
            self._on_device_result(frame)
        elif frame_type == "cancel":
            self._on_cancel(frame)
        else:
            await self._send(protocol.error(f"Unknown frame type '{frame_type}'."))

    async def _on_config(self, frame: dict[str, Any], announce: bool) -> None:
        from .config import parse_agents

        agents = parse_agents(frame.get("agents"))
        live_model = str(frame.get("live_model") or "").strip() or None
        config = await self._store.update(agents=agents or None, live_model=live_model)

        if announce:
            await self._send(
                protocol.ready(
                    agents=self._store.runnable_subagents(),
                    live_model=config.live_model,
                    config_generation=self._store.generation,
                ),
            )

    async def _on_delegate(self, frame: dict[str, Any]) -> None:
        task_id = str(frame.get("task_id") or "").strip()
        goal = str(frame.get("goal") or "").strip()
        kind = str(frame.get("kind") or "auto").strip().lower()

        if not task_id or not goal:
            await self._send(protocol.error("delegate needs both task_id and goal.", task_id or None))
            return

        if task_id in self._tasks:
            await self._send(protocol.error(f"Task {task_id} is already running.", task_id))
            return

        if not self._store.snapshot().orchestrator.is_runnable():
            await self._send(
                protocol.error(
                    "No orchestrator model configured. Set GEMINI_API_KEY (or "
                    "SARA_ORCHESTRATOR_*) on the backend.",
                    task_id,
                ),
            )
            return

        self._tasks[task_id] = asyncio.create_task(self._run_delegation(task_id, goal, kind))
        self._tasks[task_id].add_done_callback(lambda _: self._tasks.pop(task_id, None))

    def _on_device_result(self, frame: dict[str, Any]) -> None:
        task_id = str(frame.get("task_id") or "")
        call_id = str(frame.get("call_id") or "")
        bridge = self._bridges.get(task_id)
        if bridge is None:
            logger.debug("device_result for unknown task %s", task_id)
            return

        data = frame.get("data")
        payload: dict[str, Any] = dict(data) if isinstance(data, dict) else {}
        if not frame.get("ok", False):
            payload["ok"] = False
            payload.setdefault("error", frame.get("error") or "The action failed on the phone.")
        else:
            payload.setdefault("ok", True)

        bridge.resolve(call_id, payload)

    def _on_cancel(self, frame: dict[str, Any]) -> None:
        task_id = str(frame.get("task_id") or "")
        task = self._tasks.pop(task_id, None)
        if task is not None:
            task.cancel()
        bridge = self._bridges.pop(task_id, None)
        if bridge is not None:
            bridge.close()
        logger.info("Cancelled task %s", task_id)

    # ------------------------------------------------------------------ the run

    async def _run_delegation(self, task_id: str, goal: str, kind: str) -> None:
        config = self._store.snapshot()
        bridge = DeviceBridge(self._send, task_id)
        self._bridges[task_id] = bridge

        try:
            agent = build_root_agent(config, bridge)
            session_service = InMemorySessionService()
            runner = Runner(agent=agent, app_name=APP_NAME, session_service=session_service)
            await session_service.create_session(
                app_name=APP_NAME,
                user_id=USER_ID,
                session_id=task_id,
            )

            await self._send(protocol.step(task_id, "orchestrator", f"Planning: {goal}", "running"))

            message = types.Content(
                role="user",
                parts=[types.Part(text=self._goal_with_hint(goal, kind))],
            )

            final_text = ""
            async for event in runner.run_async(
                user_id=USER_ID,
                session_id=task_id,
                new_message=message,
                run_config=RunConfig(max_llm_calls=MAX_LLM_CALLS),
            ):
                text = _event_text(event)
                if text:
                    await self._send(protocol.step(task_id, event.author or "agent", text, "running"))
                if event.is_final_response() and text:
                    final_text = text

            await self._send(protocol.step(task_id, "orchestrator", "Finished", "done"))
            await self._send(
                protocol.delegate_result(
                    task_id,
                    ok=True,
                    text=final_text or "Done, but I have no summary to report.",
                ),
            )

        except asyncio.CancelledError:
            logger.info("Task %s cancelled", task_id)
            await self._safe_send(protocol.delegate_result(task_id, ok=False, text="Cancelled."))
            raise
        except Exception as exc:  # noqa: BLE001 - reported to the phone as a task failure
            logger.exception("Delegation %s failed", task_id)
            await self._safe_send(protocol.step(task_id, "orchestrator", str(exc), "failed"))
            await self._safe_send(protocol.delegate_result(task_id, ok=False, text=str(exc)))
        finally:
            bridge.close()
            self._bridges.pop(task_id, None)

    @staticmethod
    def _goal_with_hint(goal: str, kind: str) -> str:
        """ADK routes to subagents by model decision, so the app's `kind` hint is passed
        through as context in the prompt rather than as a forced route."""
        if kind == "device":
            return f"{goal}\n\n(This needs the phone: prefer the phone control subagent.)"
        if kind == "research":
            return f"{goal}\n\n(This is an information question: prefer the research subagent.)"
        return goal

    # ------------------------------------------------------------------ sending

    async def _send(self, frame: dict[str, Any]) -> None:
        async with self._send_lock:
            await self._websocket.send_text(json.dumps(frame))

    async def _safe_send(self, frame: dict[str, Any]) -> None:
        """Best-effort send used on failure paths, where the socket may already be gone."""
        try:
            await self._send(frame)
        except Exception:  # noqa: BLE001
            logger.debug("Dropped frame after disconnect: %s", frame.get("type"))


def _event_text(event: Any) -> str:
    content = getattr(event, "content", None)
    parts = getattr(content, "parts", None) or []
    chunks: list[str] = []
    for part in parts:
        text = getattr(part, "text", None)
        if text:
            chunks.append(text)
    return "\n".join(chunks).strip()
