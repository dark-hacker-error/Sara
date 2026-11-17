"""Sara backend: FastAPI app + WebSocket session endpoint.

Run it:

    cd backend
    ./run.sh                 # macOS / Linux  (creates .venv, installs, starts)
    run.bat                  # Windows

or manually:

    python -m venv .venv && source .venv/bin/activate
    pip install -r requirements.txt
    cp .env.example .env     # then put your GEMINI_API_KEY in it
    uvicorn app.main:app --host 0.0.0.0 --port 8000

`--host 0.0.0.0` matters: with the default 127.0.0.1 bind, a phone on the same Wi-Fi can
never reach it. The phone connects to `ws://<host>:8000/ws/session`.
"""

from __future__ import annotations

import logging
import os
import socket
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware

from . import protocol
from .models_api import router as models_router
from .session import SessionHandler
from .store import ConfigStore

BACKEND_VERSION = "0.2.0"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-8s %(name)s: %(message)s",
)
logger = logging.getLogger("sara")

# Load .env when present. python-dotenv ships with google-adk, so no extra dependency.
try:
    from dotenv import load_dotenv

    load_dotenv()
except ImportError:  # pragma: no cover
    logger.debug("python-dotenv not installed; relying on the real environment")

store = ConfigStore()


def _env_host() -> str:
    return os.environ.get("SARA_HOST", "0.0.0.0").strip() or "0.0.0.0"


def _env_port() -> int:
    raw = os.environ.get("SARA_PORT", "8000").strip() or "8000"
    try:
        return int(raw)
    except ValueError:
        return 8000


def _lan_hint() -> str:
    """Best-effort LAN IP for the startup banner.

    Opens a UDP socket to a public address without sending packets; this is the standard way
    to learn which local interface would be used for outbound traffic.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def _gemini_key_present() -> bool:
    return bool(
        os.environ.get("GEMINI_API_KEY", "").strip()
        or os.environ.get("SARA_ORCHESTRATOR_API_KEY", "").strip()
    )


def log_startup() -> None:
    config = store.snapshot()
    host, port = _env_host(), _env_port()
    runnable = store.runnable_subagents()

    logger.info("Sara backend %s ready (protocol v%d)", BACKEND_VERSION, protocol.PROTOCOL_VERSION)
    logger.info("Orchestrator: %s (%s)", config.orchestrator.model, config.orchestrator.provider)

    if not _gemini_key_present():
        logger.error(
            "GEMINI_API_KEY is missing. Copy .env.example to .env and add your key, then restart. "
            "Without it any delegated task will fail immediately.",
        )
    if not config.orchestrator.is_runnable():
        logger.warning(
            "Orchestrator has no usable model. Set GEMINI_API_KEY (or SARA_ORCHESTRATOR_*).",
        )

    logger.info("Subagents runnable: %s", runnable or "none (the app pushes config on connect)")
    logger.info("Listening on %s:%d", host, port)
    logger.info("Health check: http://127.0.0.1:%d/health", port)
    if host == "0.0.0.0":
        logger.info("From your phone (same Wi-Fi): http://%s:%d/health", _lan_hint(), port)
    logger.info("Phone WebSocket: ws://<host>:%d/ws/session", port)


@asynccontextmanager
async def lifespan(_: FastAPI):
    log_startup()
    yield


app = FastAPI(
    title="Sara backend",
    version=BACKEND_VERSION,
    description="Agent orchestration for the Sara Android voice assistant.",
    lifespan=lifespan,
)

# The app talks over a WebSocket, not CORS-scoped XHR, but the /models endpoint is handy from
# a browser while debugging.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(models_router)


@app.get("/health")
async def health() -> dict[str, object]:
    """Everything the Android diagnostics screen needs, in one call."""
    config = store.snapshot()
    ready = config.orchestrator.is_runnable()
    return {
        "status": "ok",
        "version": BACKEND_VERSION,
        "protocol": protocol.PROTOCOL_VERSION,
        # `adk_ready` is the field the app reads; `orchestrator_ready` is kept for older builds.
        "adk_ready": ready,
        "orchestrator_ready": ready,
        "gemini_key_present": _gemini_key_present(),
        "orchestrator_model": config.orchestrator.model,
        "subagents": [
            {"name": agent.name, "role": agent.role, "runnable": agent.is_runnable()}
            for agent in config.agents
        ],
        "config_generation": store.generation,
        "live_model": config.live_model,
    }


@app.websocket("/ws/session")
async def session_socket(websocket: WebSocket) -> None:
    await websocket.accept()
    logger.info("Phone connected from %s", websocket.client)
    handler = SessionHandler(websocket, store)
    try:
        await handler.run()
    except WebSocketDisconnect as exc:
        # 1006 here is normal for a dropped mobile connection.
        logger.info("Phone disconnected (code %s)", getattr(exc, "code", "?"))
    except Exception as exc:  # noqa: BLE001 - never let a session crash the server
        logger.exception("Session crashed")
        try:
            await websocket.send_text(
                __import__("json").dumps(protocol.error(f"Server error: {exc}")),
            )
        except Exception:  # noqa: BLE001 - socket already gone
            pass
    finally:
        logger.info("Phone session closed")


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=_env_host(),
        port=_env_port(),
        reload=False,
    )