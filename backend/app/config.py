"""Configuration models.

Two rules shape this module:

1. **Nothing about a subagent is hard-coded.** Every subagent's base URL, API key and model
   comes from config, either from the environment or pushed by the phone app at runtime.
2. **Building a model is pure.** `AgentSpec.to_model()` returns either a plain Gemini model
   string (native path) or an ADK `LiteLlm` wrapper for an OpenAI-compatible endpoint. No
   network calls happen here, so rebuilding agents on every delegation is cheap.
"""

from __future__ import annotations

import json
import os
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator

Provider = Literal["OPENAI_COMPATIBLE", "GEMINI_NATIVE"]

# Default Live model the phone should use. Not used by the backend itself — it is echoed in
# the `ready` frame so the app and server agree on what is current.
DEFAULT_LIVE_MODEL = "gemini-3.8-live"
DEFAULT_ORCHESTRATOR_MODEL = "gemini-3.8-flash"

ORCHESTRATOR_NAME = "sara_orchestrator"


class AgentSpec(BaseModel):
    """One subagent's wiring. Mirrors `AgentConfig` on the Android side."""

    id: str = ""
    name: str
    role: str = "custom"
    enabled: bool = True
    provider: Provider = "OPENAI_COMPATIBLE"
    base_url: str = ""
    api_key: str = ""
    model: str = ""
    system_prompt: str = ""

    @field_validator("provider", mode="before")
    @classmethod
    def _normalise_provider(cls, value: Any) -> Any:
        if isinstance(value, str):
            upper = value.strip().upper()
            if upper in ("GEMINI_NATIVE", "GEMINI", "NATIVE"):
                return "GEMINI_NATIVE"
            return "OPENAI_COMPATIBLE"
        return value

    @field_validator("base_url")
    @classmethod
    def _strip_trailing_slash(cls, value: str) -> str:
        return value.strip().rstrip("/")

    def is_runnable(self) -> bool:
        """Whether this spec has enough information to actually build a model.

        GEMINI_NATIVE needs credentials too, but they can come from the environment rather
        than the spec (the app never sees the backend's GEMINI_API_KEY), so either a spec-level
        key, a Vertex AI setup, or GEMINI_API_KEY in the environment counts.
        """
        if not self.enabled or not self.model:
            return False
        if self.provider == "GEMINI_NATIVE":
            if self.api_key.strip():
                return True
            import os

            return bool(
                os.environ.get("GEMINI_API_KEY", "").strip()
                or os.environ.get("GOOGLE_GENAI_USE_VERTEXAI", "").strip()
                or os.environ.get("GOOGLE_CLOUD_PROJECT", "").strip()
            )
        return bool(self.base_url and self.api_key)

    def to_model(self) -> Any:
        """Return the model in the shape ADK's `LlmAgent.model` accepts.

        - `GEMINI_NATIVE` -> the bare model string; ADK routes it through google-genai and
          picks up GEMINI_API_KEY from the environment.
        - `OPENAI_COMPATIBLE` -> `LiteLlm` with `api_base`/`api_key` forwarded to LiteLLM.
          The `openai/` prefix selects LiteLLM's OpenAI-compatible transport; `api_base`
          redirects it at the custom provider.
        """
        if self.provider == "GEMINI_NATIVE":
            return self.model

        from google.adk.models.lite_llm import LiteLlm

        return LiteLlm(
            model=f"openai/{self.model}",
            api_base=self.base_url,
            api_key=self.api_key,
        )


class AppConfig(BaseModel):
    """Everything the agent factory needs, in one immutable snapshot."""

    orchestrator: AgentSpec
    agents: list[AgentSpec] = Field(default_factory=list)
    live_model: str = DEFAULT_LIVE_MODEL

    def subagent(self, role: str) -> AgentSpec | None:
        """First enabled, runnable subagent for a role."""
        for agent in self.agents:
            if agent.role == role and agent.is_runnable():
                return agent
        return None


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def orchestrator_from_env() -> AgentSpec:
    """The planning agent. Gemini-native by default; can be pointed anywhere via env.

    The provider string is passed through unvalidated here on purpose: `AgentSpec`'s
    before-validator normalises it (a `Literal` type cannot be called directly).
    """
    return AgentSpec(
        id="orchestrator",
        name=ORCHESTRATOR_NAME,
        role="orchestrator",
        enabled=True,
        provider=_env("SARA_ORCHESTRATOR_PROVIDER", "GEMINI_NATIVE"),  # type: ignore[arg-type]
        base_url=_env("SARA_ORCHESTRATOR_BASE_URL"),
        api_key=_env("SARA_ORCHESTRATOR_API_KEY", _env("GEMINI_API_KEY")),
        model=_env("SARA_ORCHESTRATOR_MODEL", DEFAULT_ORCHESTRATOR_MODEL),
    )


def default_agents() -> list[AgentSpec]:
    """Placeholder subagents so the pipeline has the right shape before the app connects.

    The phone app overwrites these on connect. They carry empty models on purpose: a
    subagent with no model is reported in the `ready` frame as not runnable, which is
    exactly what the user should see until they pick one.
    """
    return [
        AgentSpec(id="phone", name="phone_control_agent", role="phone_control"),
        AgentSpec(id="research", name="research_agent", role="research"),
    ]


def default_config() -> AppConfig:
    return AppConfig(
        orchestrator=orchestrator_from_env(),
        agents=default_agents(),
        live_model=_env("SARA_LIVE_MODEL", DEFAULT_LIVE_MODEL),
    )


def parse_agents(raw: Any) -> list[AgentSpec]:
    """Parse the agent array from a client `hello`/`config_update` frame.

    Tolerant by design: a malformed entry is skipped rather than killing the connection,
    because a config push should never take the session down.
    """
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except json.JSONDecodeError:
            return []
    if not isinstance(raw, list):
        return []

    specs: list[AgentSpec] = []
    for entry in raw:
        if not isinstance(entry, dict):
            continue
        try:
            specs.append(AgentSpec.model_validate(entry))
        except Exception:  # noqa: BLE001 - one bad entry must not break the rest
            continue
    return specs
