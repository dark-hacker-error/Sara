"""Device bridge: the backend's hands on the phone.

The backend cannot touch a phone's UI, so every device tool call becomes a `device_action`
frame to the app, which runs it through its AccessibilityService and replies with
`device_result`. This class correlates those round trips with `asyncio.Future`s.

One instance per connection. `pending` is keyed by a generated call id, so two agents
running in parallel can never steal each other's results.
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from typing import Any, Awaitable, Callable

from . import protocol

logger = logging.getLogger(__name__)

SendFrame = Callable[[dict[str, Any]], Awaitable[None]]

DEFAULT_TIMEOUT_SECONDS = 90.0


class DeviceUnavailable(RuntimeError):
    """Raised when the phone side is gone (disconnected or timed out)."""


class DeviceBridge:
    def __init__(self, send: SendFrame, task_id: str) -> None:
        self._send = send
        self._task_id = task_id
        self._pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self._closed = False

    async def request(
        self,
        action: str,
        params: dict[str, Any] | None = None,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
    ) -> dict[str, Any]:
        """Send one device action and await the phone's reply.

        Returns the phone's `data` payload. Raises [DeviceUnavailable] on timeout or
        disconnect so the agent sees a clear failure it can reason about.
        """
        if self._closed:
            raise DeviceUnavailable("The phone disconnected before this action could run.")

        call_id = uuid.uuid4().hex
        loop = asyncio.get_running_loop()
        future: asyncio.Future[dict[str, Any]] = loop.create_future()
        self._pending[call_id] = future

        try:
            await self._send(
                protocol.device_action(self._task_id, call_id, action, params or {}),
            )
            return await asyncio.wait_for(future, timeout=timeout)
        except asyncio.TimeoutError as exc:
            raise DeviceUnavailable(
                f"The phone did not answer the '{action}' action within {timeout:.0f}s.",
            ) from exc
        finally:
            self._pending.pop(call_id, None)

    def resolve(self, call_id: str, payload: dict[str, Any]) -> None:
        """Called from the socket read loop when a `device_result` arrives."""
        future = self._pending.get(call_id)
        if future is None or future.done():
            logger.debug("Ignoring device_result for unknown call %s", call_id)
            return
        future.set_result(payload)

    def close(self) -> None:
        """Fails every outstanding action so no agent task hangs on a dead socket."""
        self._closed = True
        for call_id, future in list(self._pending.items()):
            if not future.done():
                future.set_exception(DeviceUnavailable("The phone disconnected."))
            self._pending.pop(call_id, None)
