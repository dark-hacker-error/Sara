"""Sara backend: FastAPI app + WebSocket session endpoint.

Run it:

    cd backend
    python -m venv .venv && source .venv/bin/activate
    pip install -r requirements.txt
    cp .env.example .env      # then put your GEMINI_API_KEY in it
    uvicorn app.main:app --host 0.0.0.0 --port 8000

The phone app connects to `ws://<host>:8000/ws/session`.
"""

from __future__ import annotations

import logging

from fastapi import FastAPI, WebSocket
from fastapi.middleware.cors import CORSMiddleware

from . import protocol
from .models_api import router as models_router
from .session import SessionHandler
from .store import ConfigStore

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

app = FastAPI(
    title="Sara backend",
    version=str(protocol.PROTOCOL_VERSION),
    description="Agent orchestration for the Sara Android voice assistant.",
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

store = ConfigStore()
app.include_router(models_router)


@app.on_event("startup")
async def _on_startup() -> None:
    config = store.snapshot()
    runnable = store.runnable_subagents()
    logger.info("Sara backend ready (protocol v%d)", protocol.PROTOCOL_VERSION)
    logger.info("Orchestrator: %s (%s)", config.orchestrator.model, config.orchestrator.provider)
    if not config.orchestrator.is_runnable():
        logger.warning(
            "Orchestrator has no credentials. Set GEMINI_API_KEY or SARA_ORCHESTRATOR_API_KEY.",
        )
    logger.info("Subagents runnable: %s", runnable or "none (the app pushes config on connect)")


@app.get("/health")
async def health() -> dict[str, object]:
    config = store.snapshot()
    return {
        "status": "ok",
        "protocol": protocol.PROTOCOL_VERSION,
        "orchestrator_model": config.orchestrator.model,
        "orchestrator_ready": config.orchestrator.is_runnable(),
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
    try:
        await SessionHandler(websocket, store).run()
    finally:
        logger.info("Phone session closed")
