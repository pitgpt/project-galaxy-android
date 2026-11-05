# Galaxy

Personal AI companion for Android.

## Current foundation

- Kotlin + Android Gradle Plugin
- Jetpack Compose UI
- Android application module: `app`
- Package: `com.pitgpt.galaxy`
- Minimum Android version: API 26
- JDK target: 17
- GitHub Actions debug-build workflow

## Build

The CI workflow installs Gradle 8.11.1 and runs:

```bash
gradle assembleDebug
```

## Planned architecture

1. App UI and state management
2. Voice engine (Realtime API over WebRTC)
3. Secure backend connection for ephemeral credentials
4. Local memory (Room)
5. Avatar and animation layer

The Android client must never contain a permanent OpenAI API key.
