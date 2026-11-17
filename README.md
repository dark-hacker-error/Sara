# Sara

An Android voice assistant that actually *does* things: real-time speech-to-speech over the
**Gemini Live API**, and a planner/subagent team on a **Python + FastAPI + Google ADK**
backend that can read your screen and tap it.

This is **milestone 1 (foundation) plus a setup-hardening pass**. Voice, delegation, phone
control, per-subagent model config, diagnostics and the four connection modes are wired end
to end. Section 10 is the honest list of what is still missing.

---

## 1. Architecture

```
┌────────────────────────── Android app (Kotlin + Compose) ──────────────────────────┐
│   AudioEngine            LiveSessionManager                 SaraAccessibilityService │
│   16kHz mic PCM ───────▶ (OkHttp WebSocket) ◀──── 24kHz PCM  read tree / gestures /  │
│   barge-in flush         │        ▲                          type / global actions    │
│                          │        │ toolResponse                                     │
│              wss://…BidiGenerateContent  (Gemini Live — direct, not proxied)          │
│                          │                                                           │
│                    toolCall: delegate_task(goal)                                     │
│                          ▼                                                           │
│                   DelegationRouter ──────────▶ BackendClient (own WebSocket)  ───────┼──┐
│                          ▲                            │                              │  │
│                          └──── device_action ──────────┘                              │  │
└───────────────────────────────────────────────────────────────────────────────────────┘  │
┌─────────────────────────────── Backend (Python) ─────────────────────────────────────────┐│
│  FastAPI  /ws/session ◀───────────────────────────────────────────────────────────────────┘│
│      │  per delegation: build agents from the *current* config snapshot                   │
│      ▼                                                                                    │
│   sara_orchestrator  ──transfer──▶  phone_control_agent  ──▶ DeviceBridge ──▶ (phone)      │
│      (planner)        ──transfer──▶  research_agent      ──▶ web_search / fetch_url        │
│  GET /health            → diagnostics                                       │
│  POST /v1/models/list   → {base_url}/models  (auto-fetch + free tagging)    │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Why two sockets?** Conversation stays on the direct-to-Gemini socket: one hop, lowest
latency, and it still works when the backend is asleep. Only `delegate_task` crosses to the
backend. The Live client sits behind `LiveToolExecutor`, so a server-to-server audio setup can
be added later without touching session logic.

---

## 2. Model ids — verified, not remembered

The brief asked for `gemini-3.1-flash-live-preview` as "latest" and
`gemini-2.5-flash-native-audio-latest` as fallback. Checked against
`ai.google.dev/gemini-api/docs/models/gemini-3.8-live`:

- The Live model page says **"Stable: `gemini-3.8-live`"** and the migration guide says
  *"Update your model string from `gemini-3.1-flash-live-preview` to `gemini-3.8-live`"*.
  `gemini-3.1-flash-live-preview` is still served but is the legacy preview.
- **`gemini-2.5-flash-native-audio-latest` does not exist.** The real id is
  `gemini-2.5-flash-native-audio-preview-12-2025` (date-suffixed).

Defaults shipped: `gemini-3.8-live`, with `gemini-3.1-flash-live-preview` and
`gemini-2.5-flash-native-audio-preview-12-2025` as fallbacks. All of them are **settings**
(`data/LiveCatalog.kt`), never hardcoded — and Settings now has
**Fetch Live models from Google** (`GET /v1beta/models`, filtered on
`supportedGenerationMethods: bidiGenerateContent`) so you can pick from what your key can
actually use.

Two more facts that shaped the code:

- Audio-only Live sessions are capped at 15 minutes → session resumption + reconnect.
- `behavior: NON_BLOCKING` is unsupported on 3.1 Live models → tool declarations are generated
  per model in `LiveTools.declarationsFor`.
- Live tool schemas need **uppercase** proto enums (`"OBJECT"`, `"STRING"`), verified by
  dumping `google.genai.types.Tool`. The newer Interactions API uses lowercase; copying from
  there silently breaks Live tool calling.

---

## 3. Repository layout

```
Sara/
├── .github/workflows/android-release.yml   # CI: build APK + publish release, run backend tests
├── android/
│   └── app/src/main/java/com/sara/assistant/
│       ├── core/          SaraContainer (DI), logging
│       ├── data/          settings, BackendEndpoint, LiveCatalog, remote model catalogs
│       ├── live/          LiveProtocol, LiveSessionManager, AudioEngine, LiveTools
│       ├── backend/       BackendClient, HealthChecker
│       ├── agent/         DelegationRouter
│       ├── phone/         SaraAccessibilityService, PhoneController, AppRegistry
│       ├── safety/        SafetyGuard
│       ├── service/       SaraForegroundService, OverlayBubbleService
│       └── ui/            home / onboarding / settings / diagnostics, SaraViewModel
└── backend/
    ├── run.sh, run.bat     # one-command start (venv + deps + .env + server)
    └── app/
        ├── main.py         /health, /ws/session, startup banner
        ├── config.py       AgentSpec / AppConfig (per-subagent base_url + key + model)
        ├── store.py        runtime config store (reload without restart)
        ├── session.py      per-connection orchestrator
        ├── device.py       DeviceBridge (awaits device_action results)
        ├── models_api.py   POST /v1/models/list
        └── agents/         factory.py, tools.py
```

---

## 4. Backend setup

**Python 3.11+** (google-adk's constraint). Tested on 3.12 and 3.14.

```bash
cd backend
bash run.sh            # macOS/Linux: creates .venv, installs, seeds .env, starts
run.bat                # Windows
```

Manual equivalent:

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env    # then set GEMINI_API_KEY
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

`--host 0.0.0.0` is not optional for phone testing: bound to `127.0.0.1`, a phone on the same
Wi-Fi can never connect.

Startup prints the address to try:

```
Sara backend 0.2.0 ready (protocol v1)
Listening on 0.0.0.0:8000
Health check: http://127.0.0.1:8000/health
From your phone (same Wi-Fi): http://192.168.1.23:8000/health
Phone WebSocket: ws://<host>:8000/ws/session
```

Verify:

```bash
curl -s http://127.0.0.1:8000/health | python -m json.tool
```

```json
{
  "status": "ok",
  "version": "0.2.0",
  "protocol": 1,
  "adk_ready": true,
  "gemini_key_present": true,
  "orchestrator_model": "gemini-3.8-flash",
  "subagents": [],
  "config_generation": 0,
  "live_model": "gemini-3.8-live"
}
```

`adk_ready` and `gemini_key_present` are separate on purpose: the server can be up and healthy
while still unable to run a task because no key is configured. The app shows both.

Tests (offline, no key needed):

```bash
pip install -r requirements-dev.txt
python -m pytest -q      # 16 tests
```

---

## 5. Connect the phone to the backend — four ways

The backend URL is a **setting**, stored on device. Enter a base `http://` URL; the app derives
`ws://`/`wss://` and `/health` from it (`data/BackendEndpoint.kt`). Accepted inputs:
`http://host:8000`, `https://host`, `ws://host:8000`, `wss://host/ws/session`, or a bare
`host:8000`.

### A) USB — most reliable, no Wi-Fi or internet needed

```bash
adb devices                       # your phone must appear as "device", not "unauthorized"
adb reverse tcp:8000 tcp:8000     # forwards phone's 127.0.0.1:8000 to the PC's :8000
```

App → Settings → Backend → `http://127.0.0.1:8000` → **Test connection** → **Save**.

`adb reverse` is per-connection: re-run it after unplugging the cable. It also works on mobile
data, which is why it is the recommended first setup.

### B) Same Wi-Fi

1. Start the backend with `--host 0.0.0.0` (`run.sh` already does).
2. Find the PC's LAN IPv4:
   - Windows: `ipconfig` → "IPv4 Address" (e.g. `192.168.1.23`)
   - macOS: `ipconfig getifaddr en0`
   - Linux: `ip a` or `hostname -I`
3. Allow inbound TCP 8000 in the PC firewall:
   - Windows: `netsh advfirewall firewall add rule name="Sara 8000" dir=in action=allow protocol=TCP localport=8000`
   - macOS: System Settings → Network → Firewall → Options → allow Python
   - Linux (ufw): `sudo ufw allow 8000/tcp`
4. Put the phone on the **same Wi-Fi** (turn mobile data off if the phone prefers it).
5. App → Settings → Backend → `http://192.168.1.23:8000`.

Many home routers enable "AP/client isolation", which blocks phone→PC traffic even on the same
SSID. If health says *timeout*, that is the usual culprit — use USB instead.

### C) Remote tunnel — works from anywhere

- **Cloudflare Tunnel** (free, gives https):
  ```bash
  cloudflared tunnel --url http://localhost:8000
  # prints e.g. https://random-words.trycloudflare.com
  ```
  Use `https://random-words.trycloudflare.com`. The app turns it into `wss://…/ws/session`.
- **ngrok**: `ngrok http 8000` → use the printed `https://…` URL.
- **Tailscale** (best for a permanent private address): install on PC and phone, then use the
  PC's tailnet IP, e.g. `http://100.x.y.z:8000`.

Tunnels are https, so no cleartext concerns. Don't expose `:8000` publicly without adding auth.

### D) Emulator

Backend on the host, app in the emulator: `http://10.0.2.2:8000`. (`10.0.2.2` is the
emulator's alias for the host loopback — it does **not** exist on a real phone, which is
exactly the bug in the original default.)

---

## 6. Android setup

Requirements: JDK 17, Android SDK platform 36.

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
bash ./gradlew :app:assembleDebug
```

Or use CI: push to `main`; the workflow publishes `sara-0.1.0.apk` on
[Releases](https://github.com/dark-hacker-error/Sara/releases).

On the phone:

1. Open Sara → the setup screen walks through **Microphone**, **Accessibility service**,
   **Display over other apps**, **Notifications**. It deep-links to each system screen.
2. Paste the **Gemini API key** (`aistudio.google.com`) → Settings → **Fetch Live models from
   Google** to confirm the key works and see which Live models are available.
3. Settings → Backend → enter the URL from section 5 → **Test connection** → **Save**.
4. Settings → Subagents → edit each → paste base URL + key → **Fetch available models** → pick.
5. Open **Diagnostics** and get all green. Then **Start talking**.

Build defaults: `compileSdk`/`targetSdk` 36, `minSdk` 28, AGP 8.13.2, Kotlin 2.2.21,
Gradle 8.14.3.

---

## 7. Wire protocols

### App ⇄ Gemini Live

| Direction | Payload |
| --- | --- |
| → | `{"setup": {model, responseModalities, systemInstruction, speechConfig, realtimeInputConfig{automaticActivityDetection, activityHandling, turnCoverage}, inputAudioTranscription, outputAudioTranscription, contextWindowCompression, sessionResumption, tools}}` |
| → | `{"realtimeInput": {"audio": {"data": "<b64>", "mimeType": "audio/pcm;rate=16000"}}}` |
| → | `{"realtimeInput": {"text": "…"}}` / `{"realtimeInput": {"audioStreamEnd": true}}` |
| → | `{"toolResponse": {"functionResponses": [{id, name, response}]}}` |
| ← | `setupComplete`, `serverContent{modelTurn.parts[].inlineData, inputTranscription, outputTranscription, interrupted, turnComplete}`, `toolCall`, `toolCallCancellation`, `goAway`, `sessionResumptionUpdate`, `usageMetadata` |

Two rules that matter:

1. **Nothing is sent before `setupComplete`.** The docs require it, and violating it is the
   classic cause of close code **1007**. Mic frames are dropped until then; typed text is
   queued.
2. The endpoint **often sends JSON in binary frames**, so both `onMessage(text)` and
   `onMessage(bytes)` feed the same decoder. A single frame can carry audio *and* a transcript
   *and* usage metadata, so the decoder returns a list and every event is dispatched.

Barge-in = `activityHandling: START_OF_ACTIVITY_INTERRUPTS` plus a local mic-level flush so the
cut-off does not wait for a round trip.

### App ⇄ Backend

| Direction | Frame |
| --- | --- |
| → | `hello` / `config_update` `{protocol, agents[], live_model, system_prompt}` |
| → | `delegate` `{task_id, goal, kind}` · `cancel` `{task_id}` · `device_result` `{task_id, call_id, ok, data, error}` |
| ← | `ready` `{protocol, agents[], live_model, config_generation}` |
| ← | `step` `{task_id, agent, detail, status}` |
| ← | `device_action` `{task_id, call_id, action, params}` |
| ← | `delegate_result` `{task_id, ok, text}` · `error` `{message, task_id}` |

Device actions: `tap`, `long_press`, `swipe`, `scroll`, `tap_text`, `tap_node`, `type`,
`read_screen`, `open_app`, `list_apps`, `back`, `home`, `recents`, `notifications`.

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Connection refused at http://…` | Backend not running, or bound to `127.0.0.1` | Start with `--host 0.0.0.0` (`run.sh` does) |
| `Timed out after 10s` | Firewall or AP isolation | Allow TCP 8000; disable client isolation; or use USB |
| `Host not found` | Wrong IP / different network | Re-check the LAN IPv4; both devices on one Wi-Fi |
| `Android blocked plain http://` | Cleartext policy | This build permits cleartext; if you still see it, use an https tunnel |
| `TLS handshake failed` | Self-signed certificate | Use Cloudflare/ngrok, or plain http on LAN |
| Backend connected, Gemini `error` | Live model id or key | Logcat `SaraWS` shows the close code; Settings → Fetch Live models |
| Live close **1007** | Frame rejected — usually a wrong model id or a setup field that model does not support | Pick `gemini-3.8-live` in Settings |
| Live close **1008** | Key/policy rejected | Re-copy the Gemini API key |
| `Reconnect attempts exhausted` | Was blind-retrying a permanent error | No longer retried; the code + reason + hint are shown with **Fix & retry** |

Logcat: `adb logcat -s SaraWS`.

---

## 9. Safety

- **Sensitive-action gate.** `SafetyGuard.classify` inspects the *action payload* (element text,
  typed content, app name) for money/deletion/credential markers. A match is refused the first
  time and reported to the planner as `NEEDS_CONFIRMATION`; the root agent's instruction tells
  it to stop and ask out loud. Approval lifts only after the user's Live transcript reads as an
  affirmative, and approvals are single-use.
- **Hard stop.** The Stop button and the `stop_everything` tool ("stop", "ruko", "bas") latch a
  flag that blocks further device actions and cancels the running task.
- **Honest limitation.** Confirmation matching is keyword-based: a guard rail, not a security
  boundary. A dedicated UI confirmation dialog is still on the roadmap.

---

## 10. What works / what is still a stub

Works:

- Signed APK built and published by CI (APK Signature Scheme v2, 4 ABIs, minSdk 28).
- Live session: connect, mic streaming, 24 kHz playback, AEC, barge-in, transcripts,
  resumption + reconnect, model fallback, permanent-vs-transient close handling.
- `delegate_task` → ADK planner → subagent → accessibility action → `toolResponse`.
- Accessibility: UI tree, tap by text/index/coords, long-press, swipe, scroll, type,
  back/home/recents, app launch, installed-app listing.
- Per-subagent base URL/key/model, runtime reload, `/v1/models/list` with error taxonomy.
- Settings: backend URL + Test connection, Live model fetch, diagnostics, overlay bubble.
- 16 offline backend tests.

Stubs / not verified:

| Item | Status |
| --- | --- |
| **On a real phone** | The APK builds and is signed, but no device was available here. Accessibility actions and the Live handshake have **not** been executed live. Do section 11. |
| **Screenshot / MediaProjection** | Not implemented. The agent uses the accessibility tree only. |
| **Web search** | `web_search` scrapes DuckDuckGo's no-JS endpoint; brittle by design. Gemini-native research agents use `google_search` grounding instead. |
| **Planner routing hint** | `kind` is prompt context, not a forced route (ADK routes by model decision). |
| **Retry state machine** | Orchestrator instruction + `RunConfig(max_llm_calls=25)`; no explicit retry/ask-user FSM. |
| **Vision step verification** | Tree re-read only, no pixel comparison. |
| **Release signing** | Falls back to debug signing unless `SARA_KEYSTORE_*` secrets are set. |
| **Cleartext** | Enabled for this sideloaded build (LAN IPs cannot be expressed as `<domain>` rules). Flip to `false` + https for production. |
| **Settings I/O** | `SettingsRepository` reads EncryptedSharedPreferences in its constructor on the main thread. |

---

## 11. Mujhe ab kya karna hai (5 steps)

1. `cd backend && bash run.sh` → `.env` me `GEMINI_API_KEY` daalo → script dobara chalao.
2. PC pe: `curl http://127.0.0.1:8000/health` → `"status":"ok"` dikhna chahiye.
3. Phone USB se jodo: `adb devices` (device dikhe) → `adb reverse tcp:8000 tcp:8000`.
4. App kholo → Settings → Backend → `http://127.0.0.1:8000` → **Test connection** → **Save** →
   Settings → Gemini key + **Fetch Live models** → `gemini-3.8-live` select karo.
5. **Diagnostics** kholo → sab green → **Start talking**. Red ho to wahan likha "Fix:" follow karo,
   aur Logcat: `adb logcat -s SaraWS`.

---

## 12. CI and releases

`.github/workflows/android-release.yml` runs on push to `main`, on `v*` tags, and manually:

1. `build-apk` — JDK 17 + Android SDK, `assembleRelease`, artifact upload, GitHub Release with
   `sara-<version>.apk`.
2. `backend-tests` — installs `requirements-dev.txt`, runs `pytest`.

A push to `main` refreshes the rolling `v0.1.0` release; a tag creates a new one. Optional
secrets for real signing: `SARA_KEYSTORE_PATH`, `SARA_KEYSTORE_PASSWORD`, `SARA_KEY_ALIAS`,
`SARA_KEY_PASSWORD`.

---

## 13. Secret handling

- Gemini and per-subagent keys live only in `EncryptedSharedPreferences` on the device.
- The backend reads its keys from `.env` (git-ignored).
- CI uses repository secrets only.
- **Rotate any token shared in plain text.**