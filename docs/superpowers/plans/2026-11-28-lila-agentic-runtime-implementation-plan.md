# Lila Agentic Runtime — implementation plan

**Date:** 2026-11-28  
**Architecture:** `docs/architecture/LILA_AGENTIC_RUNTIME.md`  
**Design review:** `docs/superpowers/specs/2026-11-28-lila-agentic-runtime-design.md`  
**Tracking:** [GitHub issue #144](https://github.com/pitgpt/project-galaxy-android/issues/144)  
**Status:** Approved for staged implementation; implementation remains subordinate to P0 product blockers.

## Execution rules

- Extend the existing `backend/galaxy_backend/lila_core`; no second Core, identity store, memory system or primary external orchestrator.
- Reuse current Identity, ContextAssembler, Memory, Model Router, authentication, rate limiting and encrypted persistence boundaries.
- Never start a phase while its explicit dependencies are unmet.
- Every phase is a separate reviewable vertical slice with tests and exact-HEAD CI.
- Do not change `main`, merge PRs, deploy, publish releases, modify secrets/account settings, or perform destructive data migrations without explicit authorization.
- Keep Galaxy Dev Audit and Galaxy + Lila Development separate: audit observes; development makes one justified change per run.
- P0-A/P0-B/P0-C product requirements remain above all agent-runtime implementation.

## Phase 0 — readiness and dependency reconciliation

**Goal:** prove the Core foundation is safe to extend.

Tasks:
1. Read the latest Galaxy Dev Audit, open PR chain, changed files, current HEAD/base SHAs and workflow runs.
2. Resolve/record stacked-PR divergence and pending CI before touching dependent branches.
3. Confirm SQLCipher migration/recovery, WAL, Keystore fail-closed, backup exclusions and encrypted media status (P0-A).
4. Confirm a real authenticated model-powered Core chat, owner-scoped durable history/memory, composition root, per-user rate limiting and tenant isolation (P0-B).
5. Confirm OpenAI-compatible provider configuration, LM Studio/Ollama/llama.cpp adapters, connection tests, per-conversation model selection and secret handling (P0-C).
6. Audit concrete persistence APIs and encryption guarantees for task storage.
7. Reconcile PR #48 identity schema with server Core. The Phase 0 decision is recorded in `docs/architecture/LILA_AGENTIC_RUNTIME.md`; the server identity snapshot, provider-neutral prompt envelope and authenticated Android/Desktop read path remain P0-B implementation work.

Exit criteria:
- P0-A/B/C blockers are either closed with evidence or explicitly documented as blockers.
- No unresolved owner-isolation or plaintext-secret issue in the storage path selected for task state.
- Identity migration/reconciliation design is reviewed, and the server-owned identity snapshot is implemented and covered by cross-provider/cross-owner exact-head tests before Phase 1.
- Exact-head CI is green for the dependency PR(s).

## Phase 1 — agent protocol and durable Task Store

**Goal:** create a safe, persistent task representation without executing tools yet.

Tasks:
1. Define strict versioned Goal, Plan, Step, TaskSnapshot and TaskEvent DTOs (`extra=forbid`, bounded strings/arrays/payloads).
2. Define server-derived principal ownership; never trust client-supplied principal_id, workspace root, grants or tool executable.
3. Implement an explicit task state machine: created, planning, awaiting_approval, queued, running, verifying, completed, failed, cancelled, interrupted, outcome_unknown.
4. Add owner-scoped TaskRepository interface and encrypted persistence implementation under existing Core persistence/composition boundaries.
5. Add idempotency keys, optimistic versioning/leases, event cursor and recovery semantics.
6. Ensure task history is separate from semantic memory; no hidden chain-of-thought or secret payloads in task events.
7. Add authenticated create/read/list/events/cancel endpoints. No tool execution endpoint in this phase.

Tests:
- Cross-owner read/list/cancel denied.
- Duplicate idempotency requests are stable; conflicting reuse rejected.
- Invalid transitions rejected.
- Interrupted task recovery is deterministic.
- Sensitive fields are redacted; event bounds enforced.

Exit criteria:
- Durable task lifecycle survives service restart in integration tests.
- Exact-head CI green.
- API contract documented and owner isolation proven.

## Phase 2 — Policy Gate and digest-bound approvals

**Goal:** make authorization independent from the model and planner.

Tasks:
1. Define typed capability grants with owner, task, workspace, tool, resource scope, expiry and revocation.
2. Define risk classification and default-deny policy evaluator.
3. Bind approval to immutable plan digest, exact step/tool/arguments/resource/scope and expiry.
4. Revalidate policy immediately before every tool invocation.
5. Add approve/deny/expire/revoke flows; changed plan or arguments invalidate prior approval.
6. Add global kill switch and task pause/cancel semantics.
7. Add audit events for policy decision and approval lifecycle.

Tests:
- No grant means deny.
- Expired/revoked/reused approval denied.
- Plan digest/step/arguments mismatch denied.
- Model-generated plan cannot grant capabilities.
- Prompt injection in user content, memory or tool output cannot override policy.
- Cross-owner approval access denied.

Exit criteria:
- Policy Gate is deterministic and server-side.
- Every possible tool execution path is guarded.
- Exact-head CI green.

## Phase 3 — read-only native tools

**Goal:** let Lila safely inspect an explicitly connected workspace.

Initial tools:
1. List workspace entries with bounded depth/count.
2. Read text files under a workspace root.
3. Git status and diff, read-only.

Tasks:
- Versioned Tool Registry with input/output JSON Schema, capability, risk, timeout and output limits.
- Canonical path resolution, symlink escape protection and path traversal defenses.
- Workspace registration and explicit user grant.
- Tool outputs marked untrusted and bounded before entering model context.
- No arbitrary shell, write, network, secret access or full-disk access.

Tests:
- Path traversal and symlink escape blocked.
- Unsupported file types/oversized files bounded.
- Tool output prompt injection treated as data.
- Policy Gate invoked for every tool.

Exit criteria:
- Read-only workspace/Git inspection works end-to-end in an isolated test workspace.
- No write-capable tool registered.
- Exact-head CI green.

## Phase 4 — supervised executor and verification

**Goal:** perform narrow, reviewable actions after explicit approval.

Tasks:
1. Add sequential ExecutionEngine; one active step per task initially.
2. Support only allow-listed build/test commands with fixed executable, arguments, working directory and environment.
3. Add timeout, cancellation propagation, resource budgets and output truncation.
4. Add idempotency and outcome_unknown handling; do not blindly repeat ambiguous side effects.
5. Add evidence-based VerificationEngine (exit code, test summary, re-read diff/status).
6. Add pause/resume/recovery and bounded replanning.
7. Keep network default-deny; no arbitrary shell or deployment.

Tests:
- Cancellation during execution.
- Timeout/resource budget enforcement.
- Ambiguous side effect not automatically replayed.
- Verification cannot pass solely on model assertion.
- Approval must still be valid at execution time.

Exit criteria:
- A supervised task can inspect a workspace, request approval for one allow-listed action, execute it and verify the resulting evidence.
- Audit trail is complete and redacted.
- Exact-head CI green.

## Phase 5 — Galaxy Android task experience

**Goal:** expose Core tasks without moving orchestration into the client.

Tasks:
- Show plan, status, step progress and verified results.
- Show exact approval scope and consequences.
- Approve/deny, pause/cancel and resume.
- Restore active task view after app restart.
- Handle expired approvals and stale task versions safely.

Exit criteria:
- Android is a client of the Core task API; no local permission bypass or duplicate planner.
- UI tests and authenticated integration tests pass.

## Phase 6 — Windows Desktop Agent

**Goal:** provide local workspace tools through a narrowly paired executor.

Prerequisites:
- Core task protocol, Policy Gate and supervised executor are stable.
- Separate Desktop repository availability and scope are confirmed.

Tasks:
- Pairing with explicit user confirmation and revocation.
- Authenticated encrypted channel.
- Workspace-only sandbox; no unrestricted remote shell.
- OS credential store for local secrets.
- Heartbeat, kill switch, reconnect and audit.

Exit criteria:
- Desktop executor cannot bypass Core policy and cannot access unpaired workspaces.
- Windows-specific tests and manual acceptance documented.

## Phase 7 — adapters and advanced autonomy

Order:
1. MCP adapter as a constrained tool transport, not policy owner.
2. External coding-runtime adapters (Codex/Claude Code/other) with task-scoped context and grants.
3. Parent/child subagent tasks with separate budgets, scope and audit.
4. Bounded parallel execution only after resource locking and recovery tests.
5. Scheduler/background jobs after durable cancellation, expiry and user-visible controls.
6. Reflection and memory proposals with provenance, confidence, expiry and user review.

No phase automatically expands permissions. A3 autonomy remains disabled by default until separately threat-modelled and explicitly enabled.

## Cross-cutting quality gates

- Unit, integration and end-to-end tests for every vertical slice.
- Exact-head GitHub Actions required; old green runs do not count.
- Security tests: cross-owner access, prompt injection, path traversal/symlink, expired/reused approvals, plan digest mismatch, cancellation, ambiguous side effects, secret leakage.
- Observability without hidden reasoning or raw secrets.
- Resource budgets, timeouts, idempotency and recovery.
- Data retention/export/delete policy for tasks and task events.
- Explicit distinction in reports: implemented, unit-tested, integration-tested, exact-head CI passed, merged, device/Windows verified.

## Sequencing relative to product MVP

Agentic runtime work must not displace:
- P0-A: SQLCipher, recovery/WAL, Keystore fail-closed, backup exclusions, encrypted media.
- P0-B: real model-powered authenticated Core chat, durable owner-scoped history/memory, composition root, per-user rate limiting and tenant isolation.
- P0-C: arbitrary OpenAI-compatible and local provider endpoints, LM Studio/Ollama/llama.cpp adapters, discovery, connection test, per-conversation selection and secure secrets.
- P1: ComfyUI image generation in the same conversation with durable job lifecycle and recovery.

Phase 0 may audit and close prerequisites. Phases 1–4 begin only when the corresponding security/storage and Core dependencies are met. Android UX, Desktop, adapters, subagents and scheduler follow their dependencies.

## First actionable development step

At the next Galaxy + Lila Development run:
1. Read the latest Dev Audit and verify current CI/HEAD state.
2. Recheck P0-A/B/C and stacked PR alignment.
3. Choose the highest-priority unresolved P0 blocker. Do not start agent runtime code if a P0 blocker is still open.
4. If P0-A/B/C are demonstrably complete, begin Phase 1 with protocol/repository design and tests.
5. Report evidence and exact blockers; do not create a commit just to show activity.
