"""Agent factory.

Builds the whole agent tree from one [AppConfig] snapshot plus a [DeviceBridge]. Called on
every delegation, which is what makes runtime config reload work: a config push changes the
snapshot, and the next delegation is built from it. No restart, no cache to invalidate.

Shape:

    sara_orchestrator (planner, no tools of its own)
      |- phone_control_agent   -> device tools (accessibility on the phone)
      |- research_agent        -> web tools
      |- ...                   -> any extra subagents the user configured

The orchestrator never touches the phone directly. Delegation is the only path, which keeps
one agent responsible for planning and verification.
"""

from __future__ import annotations

import logging

from google.adk.agents import LlmAgent

from ..config import AppConfig, AgentSpec, ORCHESTRATOR_NAME
from ..device import DeviceBridge
from .tools import build_device_tools, build_research_tools

logger = logging.getLogger(__name__)

ORCHESTRATOR_INSTRUCTION = """\
You are Sara's planning agent. You sit behind a voice assistant on the user's phone.

You do not talk to the user directly. Everything you return is read out loud by a voice
model, so reply in plain short sentences, in the same language the goal was written in
(Hinglish / Hindi / English). Never return markdown, bullet points, code fences or URLs
written out character by character.

How to work:
1. Split the goal into the smallest number of concrete steps that will achieve it.
2. Handle exactly one step at a time. Delegate it to the right subagent.
3. After any step that changes the screen, look at the screen snapshot that came back and
   verify the step actually happened before moving on. Do not assume.
4. If a step failed, try one different approach (different element, different app name,
   scroll to find it). If that also fails, stop and report what blocked you.
5. Only claim success if you verified it. Never invent a result.

Special cases you must handle:
- If a tool result contains "NEEDS_CONFIRMATION", the action is sensitive (money, deletion,
  passwords). Stop immediately and return a short sentence asking the user to confirm out
  loud. Do not retry it yourself and do not try to work around it.
- If a tool result says the accessibility service is unavailable, tell the user to enable it
  in Settings. There is no other way to control the phone.
- If no subagent is configured for the job, say so plainly and tell the user to set it up in
  Sara's settings.

Finish with one or two sentences the voice model can say to the user.
"""

PHONE_AGENT_INSTRUCTION = """\
You control an Android phone through its accessibility service. You are the only agent that
can touch the device.

Method:
1. Always start from `read_screen` so you know what is actually on screen. Never guess
   coordinates or assume an app's layout.
2. Prefer `tap_text` over raw `tap`, and `tap_node` over both when you already have an index
   from `read_screen`.
3. `open_app` takes a spoken name like "WhatsApp". If it fails, call `list_apps` to see what
   the device actually has.
4. To type: focus the field first (`tap_text` on it), then `type_text`.
5. Every action returns a fresh screen snapshot. Read it and confirm the action worked before
   taking the next one. If the screen did not change, the tap missed — pick a different
   element or scroll to find it.
6. Text fields in chat apps need the message box found by its hint or a nearby label; type
   the exact text the user dictated, then press the send control (often `tap_text` on "Send"
   or ">").
7. If a result says NEEDS_CONFIRMATION, stop and report it. Do not retry.

Return a short factual account of what the screen showed at the end, so the planner can
report success or failure honestly.
"""

RESEARCH_AGENT_INSTRUCTION = """\
You answer questions using the web. Use your tools rather than memory for anything current,
factual, or specific to a person, place or product.

- Use `web_search` to find candidate pages, then `fetch_url` on the most promising one or two.
- If a source is ambiguous or conflicts with another, say so instead of picking silently.
- Quote specifics (numbers, dates, names) exactly as the source has them.
- Finish with a two-sentence answer in the language of the question, no citations markup.
"""


def _build_phone_agent(spec: AgentSpec, bridge: DeviceBridge) -> LlmAgent:
    instruction = PHONE_AGENT_INSTRUCTION
    if spec.system_prompt:
        instruction = f"{instruction}\n\nExtra instructions from the user:\n{spec.system_prompt}"
    return LlmAgent(
        name=spec.name,
        model=spec.to_model(),
        description=(
            "Controls the phone: reads the screen and taps, swipes, scrolls, types, opens and "
            "closes apps."
        ),
        instruction=instruction,
        tools=build_device_tools(bridge),
    )


def _build_research_agent(spec: AgentSpec) -> LlmAgent:
    instruction = RESEARCH_AGENT_INSTRUCTION
    if spec.system_prompt:
        instruction = f"{instruction}\n\nExtra instructions from the user:\n{spec.system_prompt}"

    if spec.provider == "GEMINI_NATIVE":
        # Grounding with Google Search cannot be combined with function declarations in the
        # same request, so a Gemini-native research agent gets grounding only.
        from google.adk.tools import google_search

        tools = [google_search]
    else:
        tools = build_research_tools()

    return LlmAgent(
        name=spec.name,
        model=spec.to_model(),
        description="Answers questions using web search and page fetching.",
        instruction=instruction,
        tools=tools,
    )


def _build_generic_agent(spec: AgentSpec) -> LlmAgent:
    """A user-added subagent with a role we don't special-case. Research tools by default."""
    instruction = spec.system_prompt or (
        "You are a general helper subagent. Use your tools to accomplish the goal you are "
        "given and report the result plainly in two sentences."
    )
    return LlmAgent(
        name=spec.name,
        model=spec.to_model(),
        description=spec.system_prompt[:200] or "General purpose subagent.",
        instruction=instruction,
        tools=build_research_tools(),
    )


def build_root_agent(config: AppConfig, bridge: DeviceBridge) -> LlmAgent:
    """Assemble the orchestrator with every runnable subagent attached."""
    subagents: list[LlmAgent] = []
    skipped: list[str] = []

    for spec in config.agents:
        if spec.role == "orchestrator":
            continue
        if not spec.is_runnable():
            skipped.append(f"{spec.name} (no model/wiring)")
            continue

        try:
            if spec.role == "phone_control":
                subagents.append(_build_phone_agent(spec, bridge))
            elif spec.role == "research":
                subagents.append(_build_research_agent(spec))
            else:
                subagents.append(_build_generic_agent(spec))
        except Exception:  # noqa: BLE001 - a broken subagent must not kill the tree
            logger.exception("Could not build subagent %s", spec.name)
            skipped.append(f"{spec.name} (build failed)")

    if skipped:
        logger.info("Subagents not attached: %s", ", ".join(skipped))

    instruction = ORCHESTRATOR_INSTRUCTION
    if not subagents:
        instruction += (
            "\n\nIMPORTANT: no subagents are currently configured or enabled, so you cannot "
            "touch the phone or the web. Tell the user to add a subagent's model in Sara's "
            "settings, then return immediately."
        )
    if skipped:
        instruction += f"\n\nCurrently unusable subagents: {', '.join(skipped)}."

    return LlmAgent(
        name=ORCHESTRATOR_NAME,
        model=config.orchestrator.to_model(),
        description="Plans a goal, delegates it to specialist subagents, and verifies results.",
        instruction=instruction,
        sub_agents=subagents,
    )
