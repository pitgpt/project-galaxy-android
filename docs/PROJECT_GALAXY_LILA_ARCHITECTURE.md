# Project Galaxy + Lila Core + Lila Desktop

Status: agreed target architecture; implementation is incremental.

## Product boundary

### Project Galaxy
The multimodal companion product and primary conversation experience:
- text chat and conversation history;
- voice chat and speech services;
- image generation inside the active conversation;
- video generation and playback;
- character/reference management and user-facing settings.

Galaxy is a client/product surface, not the owner of model-provider credentials or the only home of Lila's identity.

### Lila Core
The provider-neutral intelligence and identity layer, initially developed inside the existing Galaxy FastAPI backend package `galaxy_backend/lila_core`.
It owns normalized turns, context assembly, memory interfaces, model routing, policy boundaries, and declarative state/events.
It must remain independent of Android UI and Desktop rendering so more than one client can use it.

### Lila Desktop
A separate Windows desktop application and, when repository creation is available, a separate repository.
It provides a chat window and an optional desktop companion overlay. The first milestone is visual only: show/hide, move, idle animation, poses and emotion changes. Speech and autonomous interaction are explicitly deferred.
The Desktop renderer consumes declarative avatar events; it never lets an LLM manipulate rendering layers or execute arbitrary desktop commands.

## Runtime relationship

```text
Project Galaxy clients (Android / Desktop chat)
                  |
           authenticated API
                  |
              Lila Core
       context / memory / routing
                  |
          provider adapters
                  |
        cloud and local models

Lila Core -- versioned avatar events --> Avatar Engine (Desktop / Android)
Galaxy chat -- image/video requests --> generation services
```

The same Core identity and conversation services should be reusable by Android and Desktop. The Avatar Engine is a presentation component and must not become a second memory or orchestration system.

## Desktop MVP milestones

1. Desktop shell with chat-window placeholder and avatar overlay.
2. Avatar catalog and asset manifest; choose among distinct Lila looks.
3. Avatar state machine: idle, walking, happy, curious, affectionate, serious.
4. Desktop movement, drag, screen-edge behavior, show/hide and persistence of user preferences.
5. Connect to Core events after the local renderer is stable.
6. Later: speech synchronization, richer rigging and voice/chat context.

## Engineering constraints

- Keep `main` unchanged; develop on feature branches and validate with CI.
- Do not create a second Core implementation inside Desktop.
- Do not expose provider API keys in Android or Desktop.
- Use authenticated, versioned API/event contracts. Validate every event and allowlist avatar actions.
- Keep renderer assets and animation data separate from Core contracts.
- Reuse existing Galaxy authentication/session boundaries; do not invent a weaker local bypass.
- Avoid committing generated user images or personal media to the source repository.
- No production deployment or release as part of the foundation work.

## Repository note

The existing repository is `pitgpt/project-galaxy-android`. Lila Core remains inside it for now. Lila Desktop is intended to become a separate repository; this architecture record is staged here until a dedicated repository can be provisioned. No Desktop product code is placed in the Android application module.
