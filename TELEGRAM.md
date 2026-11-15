# Telegram in the launcher

The launcher has its own Telegram client, so the senior never needs the Telegram app:
- **Messages:** Telegram private chats appear next to SMS (text only for now).
- **Calls:** Telegram voice and video calls run inside the launcher, with its own large call screen.

Caregiver instructions (connecting, calling, settings) are in [DOCS_ADMIN.md](DOCS_ADMIN.md)
([RU](DOCS_ADMIN_RU.md), [NL](DOCS_BEHEERDER_NL.md)). This file is for developers.

## Build setup
1. Get an `api_id` and `api_hash` at https://my.telegram.org (API development tools).
2. Create `telegram.properties` in the repo root (git-ignored, never commit it):

```
apiId=123456
apiHash=your_api_hash
```

Without this file the app builds normally and the Telegram button is hidden.

Toolchain: AGP 9.5.1, Gradle 9.9.1, `compileSdk = 37` (NTgCalls requires compiling against 37).

## Libraries
| Library | What it does | How it gets in |
|---|---|---|
| **TDLib** `libtdjson.so` v1.8.65 ([up9cloud/android-libtdjson](https://github.com/up9cloud/android-libtdjson)) | Telegram protocol: sign-in, chats, messages, and call signalling and key exchange | Gradle task `fetchTdlib` downloads it at build time and checks its SHA-256 (`app/build.gradle.kts`). JNI binding: `io.github.up9cloud.td.JsonClient`. |
| **NTgCalls** `io.github.pytgcalls:ntgcalls:3.0.7` ([pytgcalls/ntgcalls](https://github.com/pytgcalls/ntgcalls), LGPL-3.0) | Media for calls: WebRTC audio and video | Maven Central dependency. Adds about 14 MB to the APK (arm64 + armv7). |

TDLib has no audio engine; NTgCalls has no Telegram protocol. Together they make a full call.

## Sign-in
Phone number (pre-filled when the SIM reports it) -> login code is read automatically from the
incoming SMS (`TelegramClient.onSmsReceived`) -> optional two-step password. A helper can also sign
in by scanning a QR code from their own Telegram (Settings -> Devices -> Link Desktop Device).
The TDLib database lives in `filesDir/tdlib`; when it exists, `LauncherApp` starts the client at
launch so messages and calls arrive in the background.

## Messages
`data/telegram/TelegramClient.kt` wraps TDLib's JSON interface. Telegram conversations use the
address `tg-<chatId>` so they share the SMS UI (`MessagesScreen`). Private chats only; media shows
as a placeholder (`tg_media`).

## Calls
```
 Call button (Telegram chat)            TDLib                        NTgCalls (WebRTC)
 TgCallManager.start(userId) ──► createCall(user_id, protocol)
                                 updateCall: callStatePending ──► ringing / ringback tone
 (incoming) TgCallService.showIncoming ◄── updateCall pending, !is_outgoing
 Answer ──► TgCallManager.accept ──► acceptCall
                                 updateCall: callStateReady ──► createP2pCall(userId)
                                   (encryption_key, servers,       setStreamSources(mic, speaker)
                                    library_versions, allow_p2p)   skipExchange(key, isOutgoing)
                                                                   connectP2p(servers, versions, p2p, custom_parameters)
                       sendCallSignalingData ◄──────────────────── onSignalingData
                       updateNewCallSignalingData ───────────────► sendSignalingData
                                                                   onConnectionChange CONNECTED ─► Active
 Hang up ──► discardCall ──► callStateDiscarded ──► NTgCalls.stop
```
- TDLib does the Diffie-Hellman key exchange, so NTgCalls skips its own (`skipExchange`).
- NTgCalls methods are blocking native calls: they run on a single `tg-call-media` thread, never on
  the TDLib receiver thread or the main thread.
- Files:
  - `data/telegram/TgCallManager.kt`: state machine (`TgCall` / `TgCallPhase`), TDLib requests, NTgCalls, ringtone, audio mode and speaker.
  - `ui/telegram/TelegramCallActivity.kt`: full-screen call screen; shows over the lock screen, proximity sensor while on the earpiece, asks for the microphone.
  - `service/TgCallService.kt`: foreground service (`phoneCall|microphone`) during a call, and the incoming-call notification with a full-screen intent (`CallStyle`, channel `tg_call_v1`).
- A second incoming call while one is running is declined automatically.
- **Audio playback:** NTgCalls' DEVICE speaker opens no output on Android, so incoming audio is an
  EXTERNAL source: PCM frames (`onFrames`, PLAYBACK / MICROPHONE, 48 kHz mono, 10 ms) are played by
  `RemoteAudioPlayer` (AudioTrack, `USAGE_VOICE_COMMUNICATION`). Incoming media is named after its
  source on the other phone: their voice is the `microphone` slot, their video the `camera` slot.
- **Video:** incoming video is EXTERNAL with **auto resolution** (`VideoDescription(EXTERNAL, 0, 0, 0)`);
  a fixed size stretches portrait video. Frames are I420 and go to a WebRTC `SurfaceViewRenderer`
  via `TgCallManager.setVideoSink`. NTgCalls never reports frames from its own camera source, so we
  capture with CameraX (`LocalCamera.kt`), send each frame with `sendExternalFrame` (EXTERNAL camera,
  size from the first frame) and render the same frame as the self-view.
- `connectP2p`'s last argument is parsed as JSON in native code: an empty string throws a C++
  exception that kills the process, so pass `{}` when TDLib sends no `custom_parameters`.
- The official Telegram app (same account) rings too; while our call screen is up,
  `NotificationListener` cancels/snoozes its call notification.
- Emergency and short numbers never use Telegram (`util/TelegramCalls.isServiceNumber`).

### Calls from the Phone screen
`TelegramCalls.placeCall` (used by `makeDirectCall`): with no SIM card, or when the settings prefer
Telegram for the number, it tries an in-app call (`TelegramClient.findChatByPhone` ->
`TgCallManager.start`), then the **official Telegram app's** contact action
(`vnd.android.cursor.item/vnd.org.telegram.messenger.android.call`), then a normal call. Without a SIM
and without Telegram it shows "The call cannot be made". Emergency and short numbers always use the
phone network.

### SOS over Telegram
`SOSService`: SMS with send-result tracking; contacts the SMS did not reach get the alarm over Telegram
(`TelegramClient.sendTextConfirmed` waits for `updateMessageSendSucceeded`). Calls go round the SOS
contacts until someone talks for ~25 s (phone calls, or Telegram calls without a SIM). Every SOS text
starts with 🆘 (`SOSService.SOS_MARKER`) so a caregiver's phone with this app sounds an alarm
(`util/CaregiverAlarm.kt`). If nothing got through, `SosFailedScreen` says help is not coming.

### Debugging a call
```
adb logcat -s TgCallManager TelegramClient
```
`TgCallManager` logs the media devices NTgCalls sees, the number of servers and the library
versions passed to `connectP2p`, and every connection state change.

## Roadmap
Android Telecom integration (self-managed `ConnectionService`, so Telegram calls ring like phone
calls); Telegram calls in the Phone history and missed calls; auto-answer for starred contacts; FCM
push plus a foreground-service fallback to stay reachable; group chats; photos (one setting); closing
dormant apps on screen-off.
