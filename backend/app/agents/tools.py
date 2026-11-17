"""Tools the subagents can call.

Two groups:

- **Device tools** (`build_device_tools`) — thin async wrappers over [DeviceBridge]. Each one
  is a single device action. They return the phone's JSON result verbatim, including the
  refreshed screen snapshot after anything that changes the UI, so the agent can verify a
  step instead of assuming it worked.
- **Research tools** (`build_research_tools`) — keyless web access.

Honest note on the research tools: `web_search` scrapes DuckDuckGo's no-JS HTML endpoint and
`fetch_url` strips tags with a regex. Both are brittle by nature and will break if the target
markup changes. They exist so the research agent works out of the box with no third-party
signup. For production, swap `web_search` for a paid search API (Brave, Tavily, Serper) or
use Gemini's built-in `google_search` grounding, which the factory wires up automatically when
the research agent is configured with a Gemini-native model.
"""

from __future__ import annotations

import html
import logging
import re
from typing import Any, Callable
from urllib.parse import parse_qs, unquote, urlparse

import httpx

from ..device import DeviceBridge, DeviceUnavailable

logger = logging.getLogger(__name__)

USER_AGENT = "SaraAssistant/0.1 (+https://github.com/dark-hacker-error/Sara)"
HTTP_TIMEOUT_SECONDS = 20.0


# ---------------------------------------------------------------------------- device tools


def _device_call(bridge: DeviceBridge):
    """Wrap a device action so tool bodies stay one line and errors are uniform."""

    async def call(action: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        try:
            return await bridge.request(action, params or {})
        except DeviceUnavailable as exc:
            return {"ok": False, "error": str(exc)}
        except Exception as exc:  # noqa: BLE001 - never let a tool crash the run
            logger.exception("Device action %s failed", action)
            return {"ok": False, "error": f"{action} failed: {exc}"}

    return call


def build_device_tools(bridge: DeviceBridge) -> list[Callable[..., Any]]:
    """Tools backed by the phone's AccessibilityService."""
    call = _device_call(bridge)

    async def read_screen() -> dict[str, Any]:
        """Read the current screen as a list of elements.

        Returns each visible element with an index (`i`), its `text`, `desc`
        (content description), `class`, `clickable`/`editable` flags and pixel `bounds`.
        Call this before acting, and again after acting to verify the result.
        """
        return await call("read_screen")

    async def tap_text(query: str, exact: bool = False) -> dict[str, Any]:
        """Find the first on-screen element whose text or description matches and tap it.

        Prefer this over `tap` with coordinates: it survives layout changes. `query` is a
        case-insensitive substring match unless `exact` is true. Returns the matched text and
        a fresh screen snapshot.
        """
        return await call("tap_text", {"query": query, "exact": exact})

    async def tap(x: float, y: float) -> dict[str, Any]:
        """Tap raw screen coordinates. Use only when no element text is available."""
        return await call("tap", {"x": x, "y": y})

    async def tap_node(index: int) -> dict[str, Any]:
        """Tap the element with the given `i` index from the most recent `read_screen`."""
        return await call("tap_node", {"index": index})

    async def long_press(x: float, y: float) -> dict[str, Any]:
        """Press and hold at coordinates (for context menus)."""
        return await call("long_press", {"x": x, "y": y})

    async def swipe(
        from_x: float,
        from_y: float,
        to_x: float,
        to_y: float,
        duration_ms: int = 300,
    ) -> dict[str, Any]:
        """Drag from one point to another."""
        return await call(
            "swipe",
            {
                "from_x": from_x,
                "from_y": from_y,
                "to_x": to_x,
                "to_y": to_y,
                "duration_ms": duration_ms,
            },
        )

    async def scroll(direction: str) -> dict[str, Any]:
        """Scroll the current view. `direction` is one of: up, down, left, right."""
        return await call("scroll", {"direction": direction})

    async def type_text(text: str, submit: bool = False) -> dict[str, Any]:
        """Type into the focused text field.

        Set `submit` to true to press enter afterwards. Fails if no editable field is on
        screen — use `tap_text` first to focus the field.
        """
        return await call("type", {"text": text, "submit": submit})

    async def open_app(app: str) -> dict[str, Any]:
        """Launch an installed app by its spoken name, e.g. "WhatsApp" or "settings"."""
        return await call("open_app", {"app": app})

    async def list_apps(query: str = "") -> dict[str, Any]:
        """List installed launchable apps, optionally filtered by a name substring."""
        return await call("list_apps", {"query": query})

    async def press_back() -> dict[str, Any]:
        """Press the system Back button."""
        return await call("back", {})

    async def press_home() -> dict[str, Any]:
        """Go to the home screen."""
        return await call("home", {})

    async def open_recents() -> dict[str, Any]:
        """Open the recent-apps switcher."""
        return await call("recents", {})

    return [
        read_screen,
        tap_text,
        tap,
        tap_node,
        long_press,
        swipe,
        scroll,
        type_text,
        open_app,
        list_apps,
        press_back,
        press_home,
        open_recents,
    ]


# -------------------------------------------------------------------------- research tools

_TAG_RE = re.compile(r"<[^>]+>")
_SCRIPT_RE = re.compile(r"<(script|style|noscript)[^>]*>.*?</\1>", re.IGNORECASE | re.DOTALL)
_DDG_LINK_RE = re.compile(
    r'<a[^>]+class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>',
    re.IGNORECASE | re.DOTALL,
)
_WHITESPACE_RE = re.compile(r"\s+")


def _strip_html(raw: str) -> str:
    text = _SCRIPT_RE.sub(" ", raw)
    text = _TAG_RE.sub(" ", text)
    text = html.unescape(text)
    return _WHITESPACE_RE.sub(" ", text).strip()


def _clean_ddg_url(url: str) -> str:
    """DuckDuckGo wraps outbound links; unwrap the `uddg` parameter when present."""
    if "uddg=" not in url:
        return url
    query = parse_qs(urlparse(url).query)
    target = query.get("uddg")
    if target:
        return unquote(target[0])
    return url


def build_research_tools() -> list[Callable[..., Any]]:
    """Keyless web tools for the research agent."""

    async def web_search(query: str, max_results: int = 5) -> dict[str, Any]:
        """Search the web and return titles, URLs and snippets.

        Backed by DuckDuckGo's no-JS HTML endpoint, so it needs no API key — and it can break
        if that markup changes. Results are best-effort.
        """
        limit = max(1, min(int(max_results), 10))
        try:
            async with httpx.AsyncClient(
                timeout=HTTP_TIMEOUT_SECONDS,
                follow_redirects=True,
                headers={"User-Agent": USER_AGENT},
            ) as client:
                response = await client.post(
                    "https://html.duckduckgo.com/html/",
                    data={"q": query},
                )
                response.raise_for_status()
                body = response.text
        except httpx.HTTPError as exc:
            return {"ok": False, "error": f"Search failed: {exc}"}

        results: list[dict[str, str]] = []
        for match in _DDG_LINK_RE.finditer(body):
            if len(results) >= limit:
                break
            url = _clean_ddg_url(html.unescape(match.group(1)))
            if not url.startswith("http"):
                continue
            results.append({"title": _strip_html(match.group(2)), "url": url})

        if not results:
            return {
                "ok": False,
                "error": (
                    "No results parsed. The scraping endpoint may have changed shape; "
                    "configure a search API or a Gemini-native research model."
                ),
            }
        return {"ok": True, "results": results}

    async def fetch_url(url: str, max_chars: int = 4000) -> dict[str, Any]:
        """Fetch a web page and return its readable text.

        HTML tags and scripts are stripped. `max_chars` caps the returned body so one page
        cannot flood the model's context window.
        """
        if not url.startswith(("http://", "https://")):
            return {"ok": False, "error": "URL must start with http:// or https://"}

        limit = max(500, min(int(max_chars), 20000))
        try:
            async with httpx.AsyncClient(
                timeout=HTTP_TIMEOUT_SECONDS,
                follow_redirects=True,
                headers={"User-Agent": USER_AGENT},
            ) as client:
                response = await client.get(url)
                response.raise_for_status()
                content_type = response.headers.get("content-type", "")
                body = response.text
        except httpx.HTTPError as exc:
            return {"ok": False, "error": f"Fetch failed: {exc}"}

        text = _strip_html(body) if "html" in content_type.lower() else body
        truncated = len(text) > limit
        return {
            "ok": True,
            "url": str(response.url),
            "content": text[:limit],
            "truncated": truncated,
        }

    return [web_search, fetch_url]
