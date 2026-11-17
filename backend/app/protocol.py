"""Wire protocol between the Android app and this server.

Frames are JSON objects with a `type` field. Kept in one module so both sides can be read
side by side with `BackendClient.kt` / `DelegationRouter.kt`.

App -> server:
    {"type": "hello", "protocol": 1, "agents": [...], "live_model": "...", "system_prompt": "..."}
    {"type": "config_update", ...}                       # same shape, applied live
    {"type": "delegate", "task_id": "...", "goal": "...", "kind": "auto"}
    {"type": "device_result", "task_id": "...", "call_id": "...", "ok": true, "data": {...}, "error": null}
    {"type": "cancel", "task_id": "..."}

Server -> app:
    {"type": "ready", "protocol": 1, "agents": [...], "live_model": "...", "config_generation": 1}
    {"type": "step", "task_id": "...", "agent": "...", "detail": "...", "status": "running|done|failed"}
    {"type": "device_action", "task_id": "...", "call_id": "...", "action": "tap_text", "params": {...}}
    {"type": "delegate_result", "task_id": "...", "ok": true, "text": "..."}
    {"type": "error", "task_id": "...", "message": "..."}
"""

from __future__ import annotations

from typing import Any, Literal

PROTOCOL_VERSION = 1

DelegationKind = Literal["auto", "device", "research"]

# Event kinds mirroring the ADK Event surface we care about.
StepStatus = Literal["running", "done", "failed"]


def ready(
    agents: list[str],
    live_model: str,
    config_generation: int,
) -> dict[str, Any]:
    return {
        "type": "ready",
        "protocol": PROTOCOL_VERSION,
        "agents": agents,
        "live_model": live_model,
        "config_generation": config_generation,
    }


def step(
    task_id: str,
    agent: str,
    detail: str,
    status: StepStatus = "running",
) -> dict[str, Any]:
    return {
        "type": "step",
        "task_id": task_id,
        "agent": agent,
        "detail": detail,
        "status": status,
    }


def device_action(
    task_id: str,
    call_id: str,
    action: str,
    params: dict[str, Any],
) -> dict[str, Any]:
    return {
        "type": "device_action",
        "task_id": task_id,
        "call_id": call_id,
        "action": action,
        "params": params,
    }


def delegate_result(task_id: str, ok: bool, text: str) -> dict[str, Any]:
    return {"type": "delegate_result", "task_id": task_id, "ok": ok, "text": text}


def error(message: str, task_id: str | None = None) -> dict[str, Any]:
    frame: dict[str, Any] = {"type": "error", "message": message}
    if task_id is not None:
        frame["task_id"] = task_id
    return frame
