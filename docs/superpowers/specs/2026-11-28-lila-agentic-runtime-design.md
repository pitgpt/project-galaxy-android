# Lila Agentic Runtime Integration — Design Review

**Date:** 2026-11-28  
**Status:** Awaiting user approval  
**Repository:** `pitgpt/project-galaxy-android`  
**Canonical architecture:** `docs/architecture/LILA_AGENTIC_RUNTIME.md`

## Intent

Add first-class agentic capabilities to the existing Lila Core so Lila can accept a goal, plan typed actions, request scoped authorization, execute tools, verify results, recover interrupted tasks, and preserve useful outcomes. This serves Project Galaxy Android and the future Lila Desktop through one shared Core.

The design must preserve one canonical Lila identity and memory across cloud/local models and clients. The purpose is to make the existing Core agent-capable, not to replace it with a separate agent product.

## What the repository already provides

- A provider-neutral Core package and model adapter boundary in the open Draft PR chain beginning at #116.
- Context assembly and owner-scoped history/memory contracts (including PR #118 and subsequent Core slices).
- A turn application service and explicit provider selection/fallback behavior.
- Provider registry and local/cloud provider configuration work.
- ComfyUI image-generation service and durable image-job lifecycle in the open Draft #132 branch.
- A separate proposed Lila Agent Core baseline in open Draft PR #48, including stable identity ID `lila.primary` and an initial Kotlin identity repository.

The audit did not find an implemented universal task planner, tool registry, policy gate, action approval workflow, or general agent execution lifecycle in the inspected server Core branch. Existing avatar events and image-generation jobs do not substitute for those capabilities.

## Goals

- Extend `backend/galaxy_backend/lila_core` with an agent orchestration boundary.
- Reconcile identity from PR #48 with server-side Core without creating a second identity store.
- Reuse the existing model router, context/memory interfaces, authentication and encrypted persistence boundaries.
- Add durable owner-scoped task state, typed plans/actions, approval binding, bounded execution, cancellation, recovery, verification and an audit log.
- Start with read-only workspace tools and a narrow supervised execution profile.
- Add Android task visibility/approval UX and a paired Windows Desktop Agent in later milestones.
- Keep external runtimes as replaceable execution adapters, never canonical memory or policy owners.

## Non-goals

- Unrestricted shell, full-disk access, or default network access.
- A second Lila Core, separate identity, or parallel memory database.
- Replacing Codex/Claude Code/bb with a custom coding IDE.
- Multi-agent parallel execution in the first MVP.
- Automatic identity/personality rewrites or hidden chain-of-thought retention.
- Coupling voice transport to task execution.

## Approaches considered

### A — Extend the existing Lila Core (chosen)

Keep the orchestrator, task state, identity/memory policy and Tool Registry inside the existing Core. Add replaceable adapters for tools and external runtimes.

**Trade-off:** requires careful integration with the existing provider, memory and persistence work, but preserves identity continuity and minimizes duplicate systems.

### B — Create a separate agent service

Run an independent agent orchestrator and connect it to Lila Core through an API.

**Trade-off:** clearer process isolation, but introduces a second owner for task planning/state and risks duplicated memory, authorization and identity logic.

### C — Make an external agent framework the primary orchestrator

Adopt a framework/runtime as the control plane and delegate identity/memory to Lila Core.

**Trade-off:** faster access to framework features, but reverses the intended ownership boundary and makes Core dependent on external lifecycle semantics.

**Decision:** A. Keep Lila Core authoritative. Use a small explicit state machine initially; reassess frameworks only behind adapters after an MVP and measured needs.

## Design decisions

1. `lila.primary` remains the canonical agent identity.
2. The model router chooses a model; it does not grant tool permissions.
3. Model-generated plans are untrusted structured data and must be validated.
4. Every tool call passes an independent server-side Policy Gate.
5. Approvals bind to an immutable plan digest, exact step/tool/arguments/scope and expiry. A changed plan requires renewed approval.
6. Task state and event history are owner-scoped, durable and stored under the Core's encrypted persistence boundary.
7. The first executor is sequential and bounded. Subagents, parallel execution and scheduling are later phases.
8. Tool outputs and retrieved memories are untrusted. They cannot change system policy or permissions.
9. Results are marked complete only after evidence-based verification.
10. Durable memory/personality changes are proposals with provenance and user review; secrets and hidden model reasoning are not persisted.

## Delivery sequence

The detailed task-level plan will be authored after this design is approved. The intended milestones are:

1. Identity/Core reconciliation.
2. Typed agent protocol and durable Task Store.
3. Policy Gate, capability grants and approvals.
4. Read-only native workspace/Git tools.
5. Supervised executor, cancellation, recovery and verification.
6. Android task UX.
7. Windows Desktop Agent and pairing.
8. MCP/external runtime adapters.
9. Bounded subagents and parallel execution.
10. Scheduler and reflection/evolution workflow.

Runtime work must not displace the existing P0 obligations: encrypted persistence, real end-to-end Core chat, owner-scoped history/memory and working provider routing. Architecture and planning can proceed now; implementation dependencies are tracked explicitly.

## Acceptance criteria for the design

- A builder can identify the ownership boundary, task lifecycle, tool contract, policy gate and staged rollout without inventing a second Core.
- Identity, memory, model routing, task storage and execution responsibilities are distinct.
- Approval is bound to the exact action and plan version.
- First release scope is testable and deliberately narrow.
- Security, recovery, audit, data retention and user-control requirements are explicit.
- Existing Android, voice, provider and ComfyUI flows remain independent and usable.

## Open items for implementation planning

- Audit the concrete Core persistence implementation and confirm encryption guarantees before selecting Task Store storage.
- Decide whether the canonical identity schema should be server-owned with Android as a client/cache or whether the existing shared schema can be used without divergence.
- Define the first native tools and exact workspace pairing mechanism.
- Select an external runtime adapter only after the native agent protocol is stable.

## Review request

Please approve or correct this design before the task-level implementation plan and runtime implementation are started.
