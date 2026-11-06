# Galaxy

Personal AI companion for Android.

## Current implementation

- Kotlin, Jetpack Compose, and a single Android application module (\`app\`).
- Character profiles, per-character conversations, and scoped memory records (\`USER\`, \`CHARACTER\`, \`SHARED\`) backed by Room.
- Supabase authentication with session material protected by an Android Keystore key.
- Realtime voice-session orchestration using WebRTC, including ICE/DataChannel readiness, microphone permission handling, mute/stop controls, lease heartbeats, and transcript persistence in the selected character's conversation history.
- A FastAPI backend that validates Supabase JWTs, enforces server-side voice/character policy and credential quotas, and creates short-lived OpenAI Realtime client secrets.
- GitHub Actions checks Android unit tests and backend tests, builds a debug APK, and uploads it as the \`galaxy-debug-apk\` artifact.

## Build

The CI workflow installs Gradle 8.11.1 and runs:

\`\`\`bash
gradle testDebugUnitTest
gradle assembleDebug
\`\`\`

A Gradle Wrapper is still a planned reproducibility improvement.

The Android app reads these build-time settings:

- \`GALAXY_API_BASE_URL\` (or Gradle property \`galaxyApiBaseUrl\`)
- \`SUPABASE_URL\` (or Gradle property \`supabaseUrl\`)
- \`SUPABASE_PUBLISHABLE_KEY\` (or Gradle property \`supabasePublishableKey\`)

The API URL defaults to \`https://api.galaxy.invalid\`. Supabase values default to empty. Configure real values before expecting sign-in or voice-session creation to work. The permanent OpenAI API key belongs only on the backend and must never be placed in the Android app.

For a test APK, open the successful GitHub Actions run, download the \`galaxy-debug-apk\` artifact, unzip it, and install \`app-debug.apk\`. The artifact is a debug build, not a Play Store release. CI does not prove microphone capture, audio playback, or network behavior on a physical device. A new CI debug signing key may require uninstalling an older debug build before installation.

## Architecture

The project remains a single Gradle module, with package boundaries separating responsibilities:

- \`MainActivity\`: Android entry point and window setup
- \`ui\`: Compose screens and reusable visual components
- \`core/model\`: character, conversation, memory, and session models
- \`core/repository\`: storage contracts
- \`data/local\`: Room database, entities, and DAOs
- \`data/repository\`: Room-backed repository implementations
- \`data/auth\`: Supabase authentication and encrypted session handling
- \`data/remote\`: authenticated backend API client
- \`data/voice\`: Realtime voice orchestration and WebRTC transport
- \`backend\`: FastAPI service for authenticated ephemeral Realtime credentials

## Memory ownership

Memory records carry an explicit scope:

- \`USER\`: user-level memory
- \`CHARACTER\`: memory owned by one character
- \`SHARED\`: memory intentionally available across characters

Conversation messages are stored with a character ID, so each character's history can be retrieved independently. User-facing memory review, export, and deletion controls are not yet complete.

## Privacy and security

- Room currently stores conversation and memory data locally without database-at-rest encryption. Do not store passwords, payment credentials, or other highly sensitive secrets in it.
- Supabase authentication sessions are encrypted with an Android Keystore key. The encrypted SharedPreferences file is excluded from cloud backup and device-to-device transfer because the non-exportable Keystore key is device-bound.
- The backend keeps the permanent OpenAI API key server-side and returns only a short-lived client secret to the authenticated Android client.
- Backend production deployment and a real HTTPS API endpoint are not configured yet. The app's default API URL is a deliberate placeholder.
- STUN is configured for common NAT traversal. A managed TURN service is still needed for restrictive networks.

See [backend/README.md](backend/README.md) for backend configuration, local startup, and deployment requirements.
