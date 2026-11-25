# Lila Agent Core — Architecture Decision

Status: Proposed implementation baseline  
Scope: One first-party agent identity — Lila  
Target repository: `pitgpt/project-galaxy-android`

## Decision

Galaxy will own Lila's identity, durable memory, task orchestration, policy decisions, and tool-selection interface. External agent runtimes (for example Codex, Claude Code, or a DeepSeek Harness-compatible runtime) are optional execution providers behind adapters. They are not the owner of Lila's identity or memory and must not become a second competing orchestrator.

The first milestone is Lila only. A generalized multi-avatar memory/agent platform is explicitly out of scope until the Lila path is working end to end.

## Existing repository baseline

The Android app already has:
- `CharacterProfile` and `MemoryItem` domain models;
- `MemoryScope.USER`, `CHARACTER`, and `SHARED`;
- Room persistence and a `MemoryRepository` contract;
- conversation messages associated with a character ID;
- a separate FastAPI backend used for authenticated, short-lived Realtime voice credentials.

Therefore, do not create a parallel memory database or replace the current Android persistence layer. Extend the existing memory contracts deliberately when agent-specific records are implemented.

Known constraints:
- Room storage is currently documented as unencrypted.
- The backend is not production-deployed and its identity-provider configuration is not complete.
- The current backend is a Realtime credential service, not yet a general agent orchestrator.
- Existing voice transport must remain independent from the agent execution lifecycle.

## Logical components

### 1. Lila Identity Core
Versioned, model-independent profile containing:
- stable agent ID: `lila.primary`;
- persona and communication principles;
- long-lived goals and project context;
- decision preferences and tool-use policy;
- visual/voice identity references (metadata only; media assets remain in their existing asset storage).

Identity data is configuration and curated knowledge, not a transcript and not hidden chain-of-thought.

### 2. Galaxy Memory
Reuse the existing memory ownership model. For Lila's first milestone:
- Lila's agent memories use a dedicated owner ID / namespace (`lila.primary`);
- user memories remain user-scoped;
- conversation history remains associated with Lila's character ID;
- cross-scope retrieval is explicit and policy-controlled;
- every memory has provenance, timestamps, and a deletion path.

Do not automatically ingest every conversation or tool output. Memory writes must be selective, reviewable, and user-controllable. Do not store credentials, access tokens, or secrets as memories.

### 3. Agent Orchestrator
Owns the task lifecycle:
1. receive a user request;
2. build a bounded task context from identity and memory;
3. plan a sequence of typed actions;
4. select a tool/runtime adapter;
5. request authorization for actions that exceed the current grant;
6. execute and collect structured results;
7. verify outcomes;
8. store concise task outcomes and useful lessons;
9. report completion or blockers.

The orchestrator must have bounded iterations, deadlines, cancellation, idempotency where practical, and an auditable event log. A model-generated plan is untrusted input and must be validated before execution.

### 4. Model Router
Selects a language model independently of the execution runtime. Providers implement a common interface and declare capabilities, limits, privacy characteristics, and cost/latency metadata. The selected model may reason or generate plans; it does not itself grant permissions.

### 5. Tool Registry and Policy Gate
Tools expose typed schemas and declared capabilities. Initial capability groups:
- read project files;
- write within an explicitly approved workspace;
- run allow-listed build/test commands;
- inspect Git status and diffs;
- call configured local services such as ComfyUI.

Destructive filesystem operations, arbitrary shell commands, credential access, system configuration, publishing, and other irreversible/high-impact actions require explicit user approval. A separate policy gate validates every tool call; the LLM and external runtime cannot override it. Provide a global stop control and per-session cancellation.

### 6. Execution adapters
Adapters translate Galaxy's typed action protocol to external runtimes:
- Codex adapter;
- Claude Code adapter;
- DeepSeek Harness-compatible adapter;
- later, a native Windows Desktop Agent for capabilities not covered by a selected runtime.

Adapters are replaceable. They receive only the task-specific context and minimum capability grant. They do not receive Lila's entire private memory by default and cannot write directly to the canonical identity store.

### 7. Desktop Agent
A separate Windows process/service, introduced only after the orchestrator and tool contracts are tested. It connects to Galaxy using authenticated, encrypted transport and a device-pairing flow. It exposes a narrow tool API rather than an unrestricted remote shell. Local execution stays on the user's PC; credentials are held in the OS credential store where possible.

## Deployment boundary

- Android: user interface, voice interaction, local conversation experience, approval prompts.
- Galaxy backend: authenticated orchestration API, identity/memory access policy, task state, audit events, model routing.
- Windows Desktop Agent: local machine tools and execution adapters.
- Local model runtime: optional inference endpoint on the PC; not required for the first agent milestone.

The system must remain useful with cloud models or local models, and changing the model must not migrate or erase Lila's identity and memory.

## Initial autonomy profile

Start with supervised workspace autonomy:
- read-only inspection is allowed within configured roots;
- file writes and build/test commands are allowed only inside a paired, explicitly selected workspace and approved command set;
- network access is deny-by-default and allow-listed;
- install/uninstall, arbitrary shell, writes outside workspace, credential access, destructive actions, publication, and OS-level changes require per-action approval;
- every action records actor, tool, capability, target, approval, timestamp, and result;
- the user can pause or revoke the desktop agent at any time.

Broader autonomy can be granted later as explicit capability bundles. "Full access" is not the default.

## Implementation sequence

1. **Architecture contract (this document):** align boundaries and prevent duplicate subsystems.
2. **Identity Core v1:** define a versioned Lila identity schema and repository; no agent execution yet.
3. **Memory integration:** extend existing memory records/repositories with provenance and agent ownership without creating a second store; add migration and tests.
4. **Agent protocol:** define task, plan, action, approval, result, cancellation, and audit-event models.
5. **Tool registry + policy gate:** implement a small read-only tool set and deterministic authorization tests.
6. **First execution adapter:** integrate one existing runtime behind the protocol; compare runtime options before selecting the first adapter.
7. **Windows Desktop Agent:** pairing, authenticated transport, workspace sandbox, command allow-list, stop/revoke controls.
8. **Model routing and local inference:** add provider selection after the agent protocol is stable.
9. **Voice integration:** connect voice to the same Lila session/task context without coupling audio transport to tool execution.

## Acceptance criteria for the first usable agent

- Lila identity is loaded independently of the chosen model.
- Existing chat and memory behavior remains intact.
- Lila's memory is isolated from unrelated character data.
- A task can be planned, authorized, executed through one adapter, cancelled, and audited.
- No tool call can bypass the policy gate.
- A model/runtime can be replaced without losing canonical identity or memory.
- Android voice sessions continue to work independently of desktop-agent availability.

## Explicit non-goals for this milestone

- Multi-avatar agent orchestration.
- A bespoke replacement for Codex, Claude Code, or DeepSeek Harness.
- Unrestricted remote shell or silent full-disk access.
- Automatic self-modification of identity, policies, or permissions.
- Treating private model reasoning as a memory or audit artifact.
