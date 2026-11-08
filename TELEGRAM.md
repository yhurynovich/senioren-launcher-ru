# Telegram in the Messages app

Telegram chats appear next to SMS in Messages (text only). It uses TDLib via `libtdjson.so`
from [up9cloud/android-libtdjson](https://github.com/up9cloud/android-libtdjson) v1.8.65.
Gradle downloads it at build time and checks its SHA-256 (see `fetchTdlib` in `app/build.gradle.kts`).

## Build setup
1. Get an `api_id` and `api_hash` at https://my.telegram.org (API development tools).
2. Create `telegram.properties` in the repo root (git-ignored, never commit it):

```
apiId=123456
apiHash=your_api_hash
```

Without this file the app builds normally and the Telegram button is hidden.

## Sign-in
Phone number (pre-filled when the SIM reports it) -> login code is read automatically from the
incoming SMS -> optional two-step password. A helper can also sign in by scanning a QR code
from their own Telegram (Settings -> Devices -> Link Desktop Device).
