# Long-term memory architecture

## Status

Approved product direction: preserve the complete conversation archive and automatically curate a smaller, editable long-term memory. Retrieve relevant context at response time. The memory subsystem must be independent of the selected language model.

This document is an architecture proposal and implementation contract. It does not claim that semantic retrieval, automatic extraction, or encrypted conversation storage are already implemented.

## Product behavior

1. Keep the full conversation transcript as the source of truth. Do not silently summarize over or discard original messages.
2. After a conversation turn is committed, enqueue memory extraction as a separate, retryable background operation. A failed extractor must never lose or block the transcript.
3. Extract durable facts, preferences, decisions, ongoing projects, relationships between events, and unresolved follow-ups. Do not promote every utterance into long-term memory.
4. Preserve provenance for every extracted item: source conversation/message IDs, extraction time, model/provider identifier, and confidence.
5. Treat memory as revisable. A newer confirmed decision supersedes an older one; retain historical provenance and validity intervals rather than presenting stale facts as current.
6. Support user review, correction, pinning, and deletion. A deleted memory must not be re-created from the same source without a deliberate re-extraction action.
7. If retrieval finds no reliable evidence, the assistant must say it does not know rather than invent a recollection.

## Memory layers

### A. Conversation archive

- Store every committed user, assistant/character, and system message with stable IDs, conversation/character ownership, role, timestamp, and content.
- Keep archive retention separate from memory curation. Clearing long-term memory must not unexpectedly erase the transcript; clearing a transcript must follow explicit user intent and remove or invalidate dependent derived records.
- Add paging and bounded context-window selection. Never load the entire archive into every prompt.

### B. Curated structured memory

Use the existing scopes:

- `USER`: user-level preferences and durable facts.
- `CHARACTER`: facts and continuity owned by one character.
- `SHARED`: information deliberately available across characters.

Each memory record should carry:
- stable ID, scope, optional owner character ID, normalized content and memory type;
- importance, confidence, created/updated timestamps;
- source message/conversation IDs and provenance;
- valid-from / valid-until or superseded-by metadata;
- review state (candidate, accepted, rejected, pinned) and deletion/tombstone state.

Do not store credentials, payment secrets, authentication tokens, or other secrets as memories.

### C. Search indexes

Use a hybrid retrieval strategy rather than relying on embeddings alone:

1. Exact/full-text search for names, dates, identifiers, PR numbers, model names, and quoted phrases.
2. Semantic vector search for paraphrases and concept-level recall.
3. Structured filters and relations for scope, character, project, time, status, and supersession.

The index is derived data. It must be rebuildable from the encrypted archive and curated records. Keep source IDs so every retrieved statement can be traced to its evidence.

## Response-time context pipeline

1. Receive the new user turn and identify entities, topic, active character, and likely task.
2. Search recent conversation context first.
3. Run parallel exact/full-text, semantic, and structured-memory retrieval with scope filters.
4. Rank and deduplicate results using relevance, recency, importance, confidence, and validity.
5. Resolve conflicts: current confirmed facts outrank superseded facts; historical facts remain available when the user asks about the past.
6. Build a compact context packet containing the relevant memories, source references, timestamps, and uncertainty markers.
7. Apply a strict token budget. Prefer a few grounded facts over a large noisy retrieval.
8. Generate the answer through the selected model adapter.
9. Record retrieval/use telemetry without logging sensitive message contents.

## Automatic memory curation pipeline

- Trigger only after the transcript write succeeds.
- Use a durable queue with idempotency keys so process death and retries cannot duplicate memories.
- Extract candidate facts from a bounded conversation segment plus relevant existing memories.
- Classify each candidate by scope, type, importance, confidence, sensitivity, and source.
- Compare candidates with existing records; merge duplicates and mark changed decisions as superseding older records.
- Apply conservative acceptance thresholds. Low-confidence or ambiguous candidates remain reviewable candidates, not authoritative facts.
- Store the source links and update search indexes transactionally or through an outbox/retry mechanism.
- Keep extraction provider/model configurable; support local models first without coupling the data format to one model.

## Privacy, security, and lifecycle

- The product decision recorded in the existing project is device-only storage; no automatic cloud backup or device-to-device restore. Cross-device transfer requires a separately designed encrypted export/import flow.
- Conversation archive, memory records, provenance, and local search indexes must be protected at rest. Integrate with the SQLCipher/Room encrypted database work; do not add a parallel plaintext database.
- Embeddings can leak semantic information. Store them inside the same protected local data boundary and delete/rebuild them when their source is deleted.
- Keep raw message content out of logs, analytics, crash reports, and diagnostic metadata.
- Provide explicit controls to inspect, edit, pin, delete, and export memory. Deletion must cascade to derived embeddings and retrieval indexes.
- Respect character boundaries by default. Cross-character retrieval is allowed only for `USER` and explicitly `SHARED` records, not private `CHARACTER` memories.

## Model-independent contracts

Keep these boundaries in the domain layer:

- `MemoryExtractor`: conversation segment + existing memory snapshot -> candidate memories.
- `EmbeddingProvider`: text -> vector, with a declared model/version/dimension.
- `MemorySearchRepository`: hybrid search with scope and ownership filters.
- `MemoryConsolidator`: deduplication, conflict detection, supersession, and provenance.
- `ContextAssembler`: bounded prompt context from recent turns and retrieved evidence.
- `MemoryJobScheduler`: durable, retryable extraction/indexing jobs.

The LLM provider must not own the memory database or define its schema. Switching between cloud and local LLMs must not migrate or reset user memory. Embedding model changes require versioned indexes and a safe re-index path.

## Phased implementation

### Phase 0 — foundation and contracts
- Audit the current Room entities, repositories, text-provider request construction, and database migration chain.
- Define domain models, provenance, candidate/review states, and repository interfaces.
- Add tests for scope isolation, conflict/supersession rules, idempotency, and deletion semantics.
- Keep the existing conversation repository as the source of truth.

### Phase 1 — reliable archive and encrypted persistence
- Reconcile the existing SQLCipher/Room feature stack before adding schema changes.
- Verify that all conversation messages are persisted and can be paged after process restart.
- Add schema migrations only on the current integrated encrypted database branch; test migration and close/reopen on an Android emulator.
- Do not claim end-to-end encryption until the exact integrated head passes tests.

### Phase 2 — structured automatic memory
- Implement durable extraction jobs and candidate memory creation.
- Add provenance, review/edit/pin/delete controls, and conflict/supersession handling.
- Start with deterministic rules and the configured text model; keep the extractor replaceable.

### Phase 3 — hybrid retrieval
- Add exact/full-text retrieval, then local embeddings and semantic search.
- Version embedding models and make indexes rebuildable.
- Test retrieval quality, character isolation, stale-memory suppression, and source traceability.

### Phase 4 — context assembly and model integration
- Inject a bounded, evidence-linked context packet into every supported text/voice model path.
- Keep provider adapters model-neutral.
- Add token-budget tests and tests proving that unsupported memories are not injected across character boundaries.

### Phase 5 — local-first operation and resilience
- Add a local embedding/extraction configuration suitable for the user's available PC (RTX 5060 Ti 16 GB VRAM, 32 GB RAM) and later assess on-device constraints separately.
- Support offline queueing, retry after restart, re-indexing, and model switching without data loss.
- Validate performance and memory use on the actual Android device.

## Acceptance criteria

- A committed message survives app restart and is available in the archive.
- Memory extraction failure never loses the original message and is retried safely.
- Re-running extraction is idempotent.
- A newer confirmed decision supersedes an older one without erasing historical traceability.
- Retrieval combines exact, semantic, and structured results and returns source IDs.
- Character-private memories never leak into another character's context.
- Deleted memories and their embeddings are not returned by search.
- The same stored memory works with different LLM providers.
- Prompt context is bounded and contains evidence rather than unsupported generated recollections.
- Database migrations, encrypted-at-rest storage, backup exclusions, and deletion behavior pass automated tests before release.

## Existing implementation notes

The current text-chat baseline already has `ConversationMessage`, `MemoryItem`, `ConversationRepository`, and `MemoryRepository`, with `USER`, `CHARACTER`, and `SHARED` scopes. It stores messages by character and has a Room memory table. These are useful foundations, not a complete long-term-memory system: automatic extraction, provenance, hybrid search, vector indexing, conflict resolution, and user review controls remain to be implemented.

The repository's encrypted Room/SQLCipher work is in a separate stacked feature branch. This architecture branch intentionally starts from `feature/text-chat-engine` and contains documentation only. Before production schema work, integrate this plan with the current encrypted database stack; do not retarget or rewrite existing stacked PRs casually.
