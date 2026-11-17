# Sara

An Android voice assistant that actually *does* things: real-time speech-to-speech over the
**Gemini Live API**, and a planner/subagent team on a **Python + FastAPI + Google ADK**
backend that can read your screen and tap it.

This repository is **milestone 1: the foundation**. Voice, delegation, phone control,
per-subagent model config and the model auto-fetch flow are wired end to end. The
"what's not done yet" list at the bottom is deliberately blunt — read it before assuming
anything works.

---

## 1. Architecture

```
┌────────────────────────── Android app (Kotlin + Compose) ──────────────────────────┐
│                                                                                    │
│   AudioEngine            LiveSessionManager                 SaraAccessibilityService │
│   16kHz mic PСМ ───────▶ (OkHttp WebSocket) ◀──── 24kHz PCM  read tree / gesture /   │
│   barge-in flush         │        ▲                          type / global actions    │
│                          │        │                                                    │
│                          ▼        │ toolResponse                                     │
│              wss://…BidiGenerateContent  (Gemini Live, direct)                       │
│                          │                                                           │
│                    toolCall: delegate_task(goal)                                     │
│                          │                                                           │
│                          ▼                                                           │
│                   DelegationRouter ──────────▶ BackendClient (own WebSocket)  ───────┼──┐
│                          ▲                            │                              │  │
│                          └──── device_action ──────────┘                              │  │
└───────────────────────────────────────────────────────────────────────────────────────┘  │
                                                                                            │
┌─────────────────────────────── Backend (Python) ─────────────────────────────────────────┐│
│  FastAPI  /ws/session ◀───────────────────────────────────────────────────────────────────┘│
│      │                                                                                    │
│      │  per delegation: build agents from the *current* config snapshot                   │
│      ▼                                                                                    │
│   sara_orchestrator  ──transfer──▶  phone_control_agent  ──▶ DeviceBridge ──▶ (phone)      │
│      (planner)        ──transfer──▶  research_agent      ──▶ web_search / fetch_url /      │
│                                                              google_search grounding       │
│  POST /v1/models/list  →  {base_url}/models  (auto-fetch + free tagging)                    │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Why two sockets?** Simple conversation stays on the direct-to-Gemini socket: one hop,
lowest latency, works even when the backend is asleep. Only `delegate_task` crosses to the
backend. The Live client sits behind an interface (`LiveToolExecutor`) so a
server-to-server audio setup can be added later without touching the session logic.

---

## 2. Verified against the docs (two corrections to the original brief)

The brief asked for `gemini-3.1-flash-live-preview` with
`gemini-2.5-flash-native-audio-latest` as fallback. Checked against
`ai.google.dev/gemini-api/docs/models`:

| Brief said | Reality |
| --- | --- |
| `gemini-3.1-flash-live-preview` = latest | It is marked **legacy**. The current default Live model is **`gemini-3.8-live`**, with `gemini-3.8-live-extended-thinking` for high-reasoning voice. |
| fallback `gemini-2.5-flash-native-audio-latest` | **This id does not exist.** The real one is `gemini-2.5-flash-native-audio-preview-12-2025`. |

So the shipped default is `gemini-3.8-live`, and both the legacy ids are offered as
fallbacks. All three are **settings**, not constants (`data/LiveCatalog.kt`,
`LiveSettings.model` / `.fallbackModel`). Two further facts that shaped the code:

- Audio-only Live sessions are capped at **15 minutes**, so session resumption is
  implemented (`sessionResumption` handle + reconnect, with model fallback on failure).
- `behavior: NON_BLOCKING` (async function calling) is **not supported on the 3.1 Live
  models**, so the tool declarations are generated per model in `LiveTools.declarationsFor`.

Also confirmed by dumping `google.genai.types.Tool` to JSON: Live tool schemas need
**uppercase** proto enum types (`"OBJECT"`, `"STRING"`). The newer *Interactions* API uses
lowercase — copying from those docs silently breaks Live tool calling.

---

## 3. Repository layout

```
Sara/
├── .github/workflows/android-release.yml   # CI: build APK + publish release, run backend tests
├── android/                                # Kotlin + Jetpack Compose app
│   └── app/src/main/java/com/sara/assistant/
│       ├── SaraApp.kt, MainActivity.kt
│       ├── core/          SaraContainer (DI), logging
│       ├── data/          settings, encrypted store, LiveCatalog, remote/ModelCatalogClient
│       ├── live/          LiveProtocol, LiveSessionManager, AudioEngine, LiveTools
│       ├── backend/       BackendClient (WebSocket) + wire frames
│       ├── agent/         DelegationRouter (Live tool calls ⇄ backend)
│       ├── phone/         SaraAccessibilityService, PhoneController, AppRegistry
│       ├── safety/        SafetyGuard (confirmation gate + hard stop)
│       ├── service/       SaraForegroundService, OverlayBubbleService
│       └── ui/            theme, SaraViewModel, home / onboarding / settings screens
└── backend/                                # Python + FastAPI + ADK
    └── app/
        ├── main.py         FastAPI app, /health, /ws/session
        ├── config.py       AgentSpec / AppConfig (per-subagent base_url + key + model)
        ├── store.py        runtime config store (reload without restart)
        ├── session.py      per-connection orchestrator (Runner per delegation)
        ├── device.py       DeviceBridge (awaits device_action results)
        ├── models_api.py   POST /v1/models/list  (auto-fetch + free tagging)
        └── agents/         factory.py (agent tree), tools.py (device + research tools)
```

---

## 4. Backend setup

```bash
cd backend
python -m venv .venv && source .venv/bin/activate     # Python 3.11+ (tests run on 3.14)
pip install -r requirements.txt
cp .env.example .env        # then set GEMINI_API_KEY
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Check it:

```bash
curl -s localhost:8000/health | python -m json.tool
```

Run the tests (offline, no API key needed):

```bash
pip install -r requirements-dev.txt
python -m pytest -q          # 16 tests
```

The app connects to `ws://<your-host>:8000/ws/session`. On an emulator the host is
`10.0.2.2`; on a real phone use your machine's LAN IP and set it in Sara's Settings.

### Subagent configuration

Each subagent has its own **base URL, API key and model**, and the backend builds models
with ADK's `LiteLlm` wrapper (`openai/<model>` + `api_base`) so any OpenAI-compatible
endpoint works — OpenRouter, Together, Groq, Fireworks, LM Studio, vLLM, and Gemini itself.
Setting a subagent to `GEMINI_NATIVE` uses ADK's native Gemini path instead.

Config is pushed from the phone (`hello` / `config_update` frames) and the agent tree is
rebuilt **on every delegation** from the current snapshot. That is the whole
"reload without restart" mechanism — there is no cache to invalidate.

---

## 5. Android setup

Requirements: JDK 17, Android SDK with platform 36.

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
bash ./gradlew :app:assembleDebug          # (or ./gradlew on a real filesystem)
```

Get an APK from CI instead: push to `main` and the workflow publishes a release with
`sara-<version>.apk` attached. Install it, then:

1. Open Sara → the setup screen walks you through **Microphone**, **Accessibility
   service**, **Display over other apps** and **Notifications**.
2. Paste your **Gemini API key** (`aistudio.google.com`).
3. Settings → Backend URL → point at your running backend.
4. Settings → Subagents → **Add/Edit** → paste the base URL and key → **Fetch available
   models** → pick one. Repeat for the research agent.
5. Tap **Start talking**.

Current build defaults: `compileSdk`/`targetSdk` 36, `minSdk` 28, AGP 8.13.2, Kotlin 2.2.21,
Gradle 8.14.3.

---

## 6. Wire protocols

### App ⇄ Gemini Live

Straight from `ai.google.dev/api/live`, no invented fields:

| Direction | Payload |
| --- | --- |
| → | `{"setup": {model, responseModalities, systemInstruction, speechConfig, realtimeInputConfig{automaticActivityDetection, activityHandling, turnCoverage}, inputAudioTranscription, outputAudioTranscription, contextWindowCompression, sessionResumption, tools}}` |
| → | `{"realtimeInput": {"audio": {"data": "<b64>", "mimeType": "audio/pcm;rate=16000"}}}` |
| → | `{"realtimeInput": {"text": "…"}}` / `{"realtimeInput": {"audioStreamEnd": true}}` |
| → | `{"toolResponse": {"functionResponses": [{id, name, response}]}}` |
| ← | `setupComplete`, `serverContent{modelTurn.parts[].inlineData, inputTranscription, outputTranscription, interimInputTranscription, interrupted, turnComplete}`, `toolCall`, `toolCallCancellation`, `goAway`, `sessionResumptionUpdate`, `usageMetadata` |

Two implementation notes worth knowing: the endpoint **frequently sends JSON in *binary*
frames**, so both `onMessage(text)` and `onMessage(bytes)` feed the same decoder; and
`activityHandling: START_OF_ACTIVITY_INTERRUPTS` is what provides barge-in, backed by a
local mic-level flush so the cut-off doesn't wait for a round trip.

### App ⇄ Backend

| Direction | Frame |
| --- | --- |
| → | `hello` / `config_update` `{protocol, agents[], live_model, system_prompt}` |
| → | `delegate` `{task_id, goal, kind}` · `cancel` `{task_id}` · `device_result` `{task_id, call_id, ok, data, error}` |
| ← | `ready` `{protocol, agents[], live_model, config_generation}` |
| ← | `step` `{task_id, agent, detail, status}` (drives the task timeline) |
| ← | `device_action` `{task_id, call_id, action, params}` |
| ← | `delegate_result` `{task_id, ok, text}` · `error` `{message, task_id}` |

Device actions: `tap`, `long_press`, `swipe`, `scroll`, `tap_text`, `tap_node`, `type`,
`read_screen`, `open_app`, `list_apps`, `back`, `home`, `recents`, `notifications`.

---

## 7. Safety

- **Sensitive-action gate.** `SafetyGuard.classify` inspects the *action payload* (element
  text, typed content, app name) for money/deletion/credential markers. A match is refused
  the first time and reported to the planner as `NEEDS_CONFIRMATION`; the root agent's
  instruction tells it to stop and ask out loud. The refusal lifts only after the user's
  Live transcript reads as an affirmative, and approvals are single-use.
- **Hard stop.** The Stop button, and the `stop_everything` tool (bound to "stop", "ruko",
  "bas"), latch a stop flag that blocks further device actions and cancels the running task.
- **Honest limitation.** Confirmation matching is keyword-based. It is a guard rail, not a
  security boundary. Dedicated request/response confirmation in the UI is on the roadmap.

---

## 8. What works today

- Live session: connect, mic streaming, 24 kHz playback, echo cancellation, barge-in,
  input/output transcription, reconnect with resumption, model fallback.
- Tool calling end to end: `delegate_task` → ADK planner → subagent → device action on the
  phone → result back to Live as a `toolResponse`.
- Accessibility: read the UI tree, tap by text/index/coordinate, long-press, swipe, scroll,
  type text, back/home/recents, app launch, installed-app listing — all verified to compile
  and wired through the WebSocket round trip.
- Per-subagent base URL / key / model, runtime config reload, `/v1/models/list` auto-fetch
  with key/URL/timeout/empty error classification and free-model tagging.
- UI: onboarding with permission deep links, animated state orb (listening / thinking /
  speaking / acting), live transcript, task-step timeline, settings, floating bubble.
- 16 backend tests pass offline; the Android app compiles to a debug APK.

## 9. What is a stub or missing (read this)

| Item | Status |
| --- | --- |
| **Screenshot / MediaProjection vision** | **Not implemented.** No screen-capture code exists. Listed as optional in the brief; the agent works from the accessibility tree only. The manifest permission was removed rather than left dangling. |
| **End-to-end device run** | Verified by compilation and by the backend test suite. No emulator/device was available in the build environment, so the accessibility actions have **not** been executed against a live UI. Treat first-run testing as required. |
| **Live session against the real API** | Not exercised — that needs a live API key and a device. Protocol shape is taken from the API reference, not from a successful handshake. |
| **Web search quality** | `web_search` scrapes DuckDuckGo's no-JS HTML endpoint and `fetch_url` strips tags by regex. Brittle by design and documented as such. Gemini-native research agents use `google_search` grounding instead. |
| **Subagent planner routing** | The app's `kind` hint (`device`/`research`) is passed to the planner as prompt context. ADK routes by model decision, so a hint is a nudge, not a route. |
| **Multi-step retry policy** | Expressed in the orchestrator's instruction plus `RunConfig(max_llm_calls=25)`. There is no hard state machine with explicit retry/ask-user transitions yet. |
| **Vision-based step verification** | Verification is limited to re-reading the accessibility tree. No pixel comparison. |
| **Release signing** | CI falls back to debug signing unless `SARA_KEYSTORE_*` secrets are set. Debug-signed builds can't upgrade over each other from different CIs. |
| **Streaming TTS of planner output** | Planner text arrives as a whole per event; no incremental speech while a long task runs. |
| **EncryptedSharedPreferences deprecation** | `androidx.security:security-crypto` is deprecated upstream with no drop-in replacement. Used as requested; expect a deprecation warning. |

---

## 10. CI and releases

`.github/workflows/android-release.yml` runs on every push to `main`, on `v*` tags, and on
manual dispatch:

1. `build-apk` — sets up JDK 17 + Android SDK, builds `assembleRelease`, uploads the APK as
   an artifact, then creates or updates a GitHub Release with `sara-<version>.apk`.
2. `backend-tests` — installs `requirements-dev.txt` and runs `pytest`.

Version comes from the tag (`v0.2.0` → `0.2.0`), a manual input, or `0.1.<run-number>`.

To publish a real release build, add these repository secrets: `SARA_KEYSTORE_PATH`,
`SARA_KEYSTORE_PASSWORD`, `SARA_KEY_ALIAS`, `SARA_KEY_PASSWORD`. Without them the workflow
still ships an installable (debug-signed) APK.

---

## 11. Secret handling

- The **Gemini API key** and every **subagent API key** live only in
  `EncryptedSharedPreferences` on the device. They are never written to logs or to the repo.
- The backend reads its own keys from `.env`, which is git-ignored.
- CI uses repository secrets only.
- **Rotate any token that has been shared in plain text.** A token pasted into a chat or
  commit message should be treated as compromised and revoked at
  `github.com/settings/tokens`.
