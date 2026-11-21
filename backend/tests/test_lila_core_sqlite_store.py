import asyncio
import sqlite3
import stat

import pytest

from galaxy_backend.lila_core.contracts import CoreTurnResponse
from galaxy_backend.lila_core.memory import (
    IdempotencyKeyConflict,
    IdempotentTurnInProgress,
    MemorySnippet,
)
from galaxy_backend.lila_core.sqlite_store import LilaStoreIntegrityError, SqliteLilaStore

KEY = bytes.fromhex("3031323334353637383961626364656630313233343536373839616263646566")


def run(coro):
    return asyncio.run(coro)


def test_history_and_memory_survive_recreation_and_are_owner_scoped(tmp_path):
    path = tmp_path / "lila.sqlite3"
    first = SqliteLilaStore(path, KEY)
    run(first.append_turn(
        principal_id="owner-a",
        conversation_id="shared",
        user_message="Привет",
        assistant_message="Здравствуй",
    ))
    run(first.append_turn(
        principal_id="owner-b",
        conversation_id="shared",
        user_message="Секрет B",
        assistant_message="Ответ B",
    ))
    run(first.save_memory(
        principal_id="owner-a",
        memory=MemorySnippet(memory_id="m1", content="Пользователь любит космос"),
    ))

    second = SqliteLilaStore(path, KEY)
    owner_a = run(second.load_recent_messages(
        principal_id="owner-a", conversation_id="shared", limit=10
    ))
    owner_b = run(second.load_recent_messages(
        principal_id="owner-b", conversation_id="shared", limit=10
    ))
    assert [message.content for message in owner_a] == ["Привет", "Здравствуй"]
    assert [message.content for message in owner_b] == ["Секрет B", "Ответ B"]
    assert run(second.search_relevant_memories(
        principal_id="owner-a", query="космос", limit=8
    ))[0].content == "Пользователь любит космос"
    assert run(second.search_relevant_memories(
        principal_id="owner-b", query="космос", limit=8
    )) == ()


def test_database_does_not_contain_plaintext_owner_ids_or_content(tmp_path):
    path = tmp_path / "lila.sqlite3"
    store = SqliteLilaStore(path, KEY)
    run(store.append_turn(
        principal_id="private-owner-123",
        conversation_id="private-conversation-456",
        user_message="private user phrase",
        assistant_message="private assistant phrase",
    ))
    run(store.save_memory(
        principal_id="private-owner-123",
        memory=MemorySnippet(memory_id="private-memory-id", content="private memory phrase classified nebula token"),
    ))
    database_bytes = b"".join(
        candidate.read_bytes()
        for candidate in (
            path,
            path.with_name(path.name + "-wal"),
            path.with_name(path.name + "-shm"),
        )
        if candidate.exists()
    )
    for value in (
        b"private-owner-123",
        b"private-conversation-456",
        b"private-memory-id",
        b"private user phrase",
        b"private assistant phrase",
        b"private memory phrase",
        b"classified",
        b"nebula",
        b"token",
    ):
        assert value not in database_bytes


def test_wrong_key_fails_closed(tmp_path):
    path = tmp_path / "lila.sqlite3"
    run(SqliteLilaStore(path, KEY).append_turn(
        principal_id="owner", conversation_id="chat",
        user_message="hello", assistant_message="world",
    ))
    wrong_key = bytes.fromhex("6665646362613938373635343332313066656463626139383736353433323130")
    with pytest.raises(LilaStoreIntegrityError):
        run(SqliteLilaStore(path, wrong_key).initialize())


def test_ciphertext_tampering_fails_closed(tmp_path):
    path = tmp_path / "lila.sqlite3"
    run(SqliteLilaStore(path, KEY).append_turn(
        principal_id="owner", conversation_id="chat",
        user_message="hello", assistant_message="world",
    ))
    database = sqlite3.connect(path)
    row = database.execute("SELECT ciphertext FROM lila_messages LIMIT 1").fetchone()
    damaged = bytearray(row[0])
    damaged[-1] ^= 0x01
    database.execute("UPDATE lila_messages SET ciphertext=?", (bytes(damaged),))
    database.commit()
    database.close()

    with pytest.raises(LilaStoreIntegrityError):
        run(SqliteLilaStore(path, KEY).load_recent_messages(
            principal_id="owner", conversation_id="chat", limit=10
        ))


def test_append_turn_rolls_back_both_messages_on_partial_failure(tmp_path, monkeypatch):
    store = SqliteLilaStore(tmp_path / "lila.sqlite3", KEY)
    original_encrypt = store._encrypt
    calls = 0

    def fail_second_encrypt(plaintext, aad):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise RuntimeError("injected encryption failure")
        return original_encrypt(plaintext, aad)

    monkeypatch.setattr(store, "_encrypt", fail_second_encrypt)
    with pytest.raises(RuntimeError, match="injected encryption failure"):
        run(store.append_turn(
            principal_id="owner", conversation_id="chat",
            user_message="hello", assistant_message="world",
        ))
    monkeypatch.setattr(store, "_encrypt", original_encrypt)
    assert run(store.load_recent_messages(
        principal_id="owner", conversation_id="chat", limit=10
    )) == ()


def test_memory_upsert_and_delete_are_owner_scoped(tmp_path):
    store = SqliteLilaStore(tmp_path / "lila.sqlite3", KEY)
    run(store.save_memory(
        principal_id="owner-a",
        memory=MemorySnippet(memory_id="same-id", content="first fact"),
    ))
    run(store.save_memory(
        principal_id="owner-b",
        memory=MemorySnippet(memory_id="same-id", content="second fact"),
    ))
    run(store.save_memory(
        principal_id="owner-a",
        memory=MemorySnippet(memory_id="same-id", content="updated fact"),
    ))

    assert run(store.search_relevant_memories(
        principal_id="owner-a", query="updated", limit=5
    ))[0].content == "updated fact"
    assert run(store.search_relevant_memories(
        principal_id="owner-a", query="first", limit=5
    )) == ()
    assert run(store.search_relevant_memories(
        principal_id="owner-b", query="second", limit=5
    ))[0].content == "second fact"
    assert run(store.delete_memory(principal_id="owner-a", memory_id="same-id")) is True
    assert run(store.delete_memory(principal_id="owner-a", memory_id="same-id")) is False
    assert run(store.search_relevant_memories(
        principal_id="owner-b", query="second", limit=5
    ))[0].content == "second fact"


def test_concurrent_turn_appends_keep_each_pair_atomic(tmp_path):
    store = SqliteLilaStore(tmp_path / "lila.sqlite3", KEY)

    async def append_many():
        await asyncio.gather(*[
            store.append_turn(
                principal_id="owner",
                conversation_id="chat",
                user_message=f"user-{index}",
                assistant_message=f"assistant-{index}",
            )
            for index in range(12)
        ])

    run(append_many())
    messages = run(store.load_recent_messages(
        principal_id="owner", conversation_id="chat", limit=100
    ))
    assert len(messages) == 24
    for offset in range(0, len(messages), 2):
        assert messages[offset].role == "user"
        assert messages[offset + 1].role == "assistant"
        user_id = messages[offset].content.removeprefix("user-")
        assistant_id = messages[offset + 1].content.removeprefix("assistant-")
        assert user_id == assistant_id


def test_database_uses_wal_and_private_file_mode(tmp_path):
    path = tmp_path / "lila.sqlite3"
    store = SqliteLilaStore(path, KEY)
    run(store.initialize())
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    database = sqlite3.connect(path)
    assert database.execute("PRAGMA journal_mode").fetchone()[0].lower() == "wal"
    assert database.execute("PRAGMA synchronous").fetchone()[0] == 2
    database.close()


def test_rejects_relative_path_and_wrong_key_length(tmp_path):
    with pytest.raises(ValueError):
        SqliteLilaStore("relative.sqlite3", KEY)
    with pytest.raises(ValueError):
        SqliteLilaStore(tmp_path / "lila.sqlite3", b"short")


def test_schema_v1_migration_backfills_existing_encrypted_memories(tmp_path, monkeypatch):
    path = tmp_path / "lila.sqlite3"
    original = SqliteLilaStore(path, KEY)
    run(original.append_turn(
        principal_id="owner-a",
        conversation_id="legacy-chat",
        user_message="history survives schema migration",
        assistant_message="confirmed",
    ))
    for index in range(3):
        run(original.save_memory(
            principal_id="owner-a",
            memory=MemorySnippet(
                memory_id=f"legacy-memory-{index}",
                content=f"legacy nebula fact {index} remains searchable after migration",
            ),
        ))

    database = sqlite3.connect(path)
    database.execute("DROP TABLE lila_memory_terms")
    database.execute("PRAGMA user_version=1")
    database.commit()
    database.close()

    monkeypatch.setattr(SqliteLilaStore, "_MEMORY_BACKFILL_BATCH", 2)
    migrated = SqliteLilaStore(path, KEY)
    results = run(migrated.search_relevant_memories(
        principal_id="owner-a", query="legacy nebula", limit=8
    ))
    assert {item.memory_id for item in results} == {
        "legacy-memory-0", "legacy-memory-1", "legacy-memory-2"
    }
    history = run(migrated.load_recent_messages(
        principal_id="owner-a", conversation_id="legacy-chat", limit=10
    ))
    assert [item.content for item in history] == [
        "history survives schema migration", "confirmed"
    ]
    database = sqlite3.connect(path)
    assert database.execute("PRAGMA user_version").fetchone()[0] == 4
    assert database.execute(
        "SELECT COUNT(DISTINCT memory_key) FROM lila_memory_terms"
    ).fetchone()[0] == 3
    database.close()


def test_memory_search_finds_old_relevant_item_and_caps_decryption(tmp_path, monkeypatch):
    store = SqliteLilaStore(tmp_path / "lila.sqlite3", KEY)
    run(store.save_memory(
        principal_id="owner",
        memory=MemorySnippet(memory_id="old-memory", content="ancient nebula fact"),
    ))
    for index in range(12):
        run(store.save_memory(
            principal_id="owner",
            memory=MemorySnippet(
                memory_id=f"recent-{index}",
                content=f"shared common topic distractor {index}",
            ),
        ))

    decrypt_calls = 0
    original_decrypt = store._decrypt

    def count_decrypts(nonce, ciphertext, aad):
        nonlocal decrypt_calls
        decrypt_calls += 1
        return original_decrypt(nonce, ciphertext, aad)

    monkeypatch.setattr(store, "_decrypt", count_decrypts)
    old_result = run(store.search_relevant_memories(
        principal_id="owner", query="ancient nebula", limit=8
    ))
    assert [item.memory_id for item in old_result] == ["old-memory"]
    assert decrypt_calls == 1

    decrypt_calls = 0
    monkeypatch.setattr(SqliteLilaStore, "_MAX_MEMORY_CANDIDATES", 3)
    common_results = run(store.search_relevant_memories(
        principal_id="owner", query="shared", limit=8
    ))
    assert len(common_results) <= 3
    assert decrypt_calls <= 3


def test_memory_ciphertext_tampering_fails_closed(tmp_path):
    path = tmp_path / "lila.sqlite3"
    store = SqliteLilaStore(path, KEY)
    run(store.save_memory(
        principal_id="owner",
        memory=MemorySnippet(memory_id="m1", content="sensitive nebula fact"),
    ))

    database = sqlite3.connect(path)
    row = database.execute(
        "SELECT ciphertext FROM lila_memories LIMIT 1"
    ).fetchone()
    damaged = bytearray(row[0])
    damaged[-1] ^= 0x01
    database.execute("UPDATE lila_memories SET ciphertext=?", (bytes(damaged),))
    database.commit()
    database.close()

    with pytest.raises(LilaStoreIntegrityError):
        run(store.search_relevant_memories(
            principal_id="owner", query="nebula", limit=8
        ))


def test_memory_term_index_is_owner_bound_and_delete_cascades(tmp_path):
    store = SqliteLilaStore(tmp_path / "lila.sqlite3", KEY)
    run(store.save_memory(
        principal_id="owner-a",
        memory=MemorySnippet(memory_id="same", content="shared private term"),
    ))
    run(store.save_memory(
        principal_id="owner-b",
        memory=MemorySnippet(memory_id="same", content="shared private term"),
    ))

    owner_a_key = store._owner_key("owner-a")
    owner_b_key = store._owner_key("owner-b")
    assert store._term_keys(owner_a_key, "shared") != store._term_keys(
        owner_b_key, "shared"
    )
    assert run(store.search_relevant_memories(
        principal_id="owner-b", query="shared", limit=5
    ))[0].memory_id == "same"

    assert run(store.delete_memory(
        principal_id="owner-a", memory_id="same"
    )) is True
    database = sqlite3.connect(store._path)
    remaining = database.execute(
        "SELECT COUNT(*) FROM lila_memory_terms WHERE owner_key=?",
        (owner_a_key,),
    ).fetchone()[0]
    database.close()
    assert remaining == 0
    assert run(store.search_relevant_memories(
        principal_id="owner-a", query="shared", limit=5
    )) == ()


def test_empty_memory_query_decrypts_only_requested_recent_window(tmp_path, monkeypatch):
    store = SqliteLilaStore(tmp_path / "lila.sqlite3", KEY)
    for index in range(7):
        run(store.save_memory(
            principal_id="owner",
            memory=MemorySnippet(
                memory_id=f"memory-{index}",
                content=f"memory fact number {index}",
            ),
        ))

    decrypt_calls = 0
    original_decrypt = store._decrypt

    def count_decrypts(nonce, ciphertext, aad):
        nonlocal decrypt_calls
        decrypt_calls += 1
        return original_decrypt(nonce, ciphertext, aad)

    monkeypatch.setattr(store, "_decrypt", count_decrypts)
    results = run(store.search_relevant_memories(
        principal_id="owner", query="!!!", limit=2
    ))
    assert len(results) == 2
    assert decrypt_calls == 2



def test_idempotent_turn_replays_encrypted_response_and_rejects_key_reuse(tmp_path):
    path = tmp_path / "lila.sqlite3"
    store = SqliteLilaStore(path, KEY)
    key = "00000000-0000-4000-8000-000000000123"

    assert run(store.reserve_idempotent_turn(
        principal_id="owner-a",
        conversation_id="chat",
        idempotency_key=key,
        user_message="private idempotent user message",
    )) is None
    with pytest.raises(IdempotentTurnInProgress):
        run(store.reserve_idempotent_turn(
            principal_id="owner-a",
            conversation_id="chat",
            idempotency_key=key,
            user_message="private idempotent user message",
        ))

    response = CoreTurnResponse(
        conversation_id="chat",
        text="private idempotent assistant response",
        provider_id="cloud-primary",
        model_id="model-a",
    )
    run(store.complete_idempotent_turn(
        principal_id="owner-a",
        conversation_id="chat",
        idempotency_key=key,
        user_message="private idempotent user message",
        assistant_message=response.text,
        response=response,
    ))

    replay = run(store.reserve_idempotent_turn(
        principal_id="owner-a",
        conversation_id="chat",
        idempotency_key=key,
        user_message="private idempotent user message",
    ))
    assert replay == response

    with pytest.raises(IdempotencyKeyConflict):
        run(store.reserve_idempotent_turn(
            principal_id="owner-a",
            conversation_id="chat",
            idempotency_key=key,
            user_message="different message",
        ))

    # The same raw key is independent for another authenticated owner.
    assert run(store.reserve_idempotent_turn(
        principal_id="owner-b",
        conversation_id="chat",
        idempotency_key=key,
        user_message="private idempotent user message",
    )) is None

    database_bytes = b"".join(
        candidate.read_bytes()
        for candidate in (
            path,
            path.with_name(path.name + "-wal"),
            path.with_name(path.name + "-shm"),
        )
        if candidate.exists()
    )
    for value in (
        key.encode(),
        b"private idempotent user message",
        b"private idempotent assistant response",
    ):
        assert value not in database_bytes

    history = run(store.load_recent_messages(
        principal_id="owner-a", conversation_id="chat", limit=10
    ))
    assert [(item.role, item.content) for item in history] == [
        ("user", "private idempotent user message"),
        ("assistant", "private idempotent assistant response"),
    ]
    page, cursor = run(store.load_conversation_page(
        principal_id="owner-a", conversation_id="chat", limit=20
    ))
    assert cursor is None
    assert page[0].provider_id is None and page[0].model_id is None
    assert page[1].provider_id == "cloud-primary"
    assert page[1].model_id == "model-a"


def test_provider_model_identity_is_persisted_with_assistant_history_after_restart(tmp_path):
    path = tmp_path / "lila.sqlite3"
    store = SqliteLilaStore(path, KEY)
    run(store.append_turn(
        principal_id="owner",
        conversation_id="chat",
        user_message="Привет",
        assistant_message="Ответ",
        provider_id="local-ollama",
        model_id="qwen-local",
    ))

    reopened = SqliteLilaStore(path, KEY)
    page, cursor = run(reopened.load_conversation_page(
        principal_id="owner", conversation_id="chat", limit=20
    ))

    assert cursor is None
    assert [(item.role, item.provider_id, item.model_id) for item in page] == [
        ("user", None, None),
        ("assistant", "local-ollama", "qwen-local"),
    ]


def test_releasing_pre_provider_reservation_allows_safe_retry(tmp_path):
    store = SqliteLilaStore(tmp_path / "lila.sqlite3", KEY)
    values = {
        "principal_id": "owner",
        "conversation_id": "chat",
        "idempotency_key": "00000000-0000-4000-8000-000000000124",
        "user_message": "hello",
    }
    assert run(store.reserve_idempotent_turn(**values)) is None
    run(store.release_idempotent_turn(**values))
    assert run(store.reserve_idempotent_turn(**values)) is None


def test_schema_v3_migration_adds_turn_metadata_without_data_loss(tmp_path):
    path = tmp_path / "lila.sqlite3"
    original = SqliteLilaStore(path, KEY)
    run(original.append_turn(
        principal_id="owner",
        conversation_id="legacy-chat",
        user_message="legacy history",
        assistant_message="legacy answer",
    ))

    database = sqlite3.connect(path)
    database.execute("DROP TABLE lila_turn_metadata")
    database.execute("PRAGMA user_version=3")
    database.commit()
    database.close()

    migrated = SqliteLilaStore(path, KEY)
    run(migrated.initialize())
    page, cursor = run(migrated.load_conversation_page(
        principal_id="owner", conversation_id="legacy-chat", limit=20
    ))
    assert cursor is None
    assert [item.content for item in page] == ["legacy history", "legacy answer"]
    assert page[1].provider_id is None and page[1].model_id is None

    database = sqlite3.connect(path)
    assert database.execute("PRAGMA user_version").fetchone()[0] == 4
    assert database.execute(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='lila_turn_metadata'"
    ).fetchone()[0] == 1
    database.close()


def test_schema_v2_migration_adds_idempotency_ledger_without_data_loss(tmp_path):
    path = tmp_path / "lila.sqlite3"
    original = SqliteLilaStore(path, KEY)
    run(original.append_turn(
        principal_id="owner",
        conversation_id="legacy-chat",
        user_message="legacy history survives schema v3",
        assistant_message="confirmed",
    ))
    run(original.save_memory(
        principal_id="owner",
        memory=MemorySnippet(memory_id="legacy-memory", content="legacy memory survives schema v3"),
    ))

    database = sqlite3.connect(path)
    database.execute("DROP TABLE lila_turn_requests")
    database.execute("PRAGMA user_version=2")
    database.commit()
    database.close()

    migrated = SqliteLilaStore(path, KEY)
    run(migrated.initialize())
    assert [item.content for item in run(migrated.load_recent_messages(
        principal_id="owner", conversation_id="legacy-chat", limit=10
    ))] == ["legacy history survives schema v3", "confirmed"]
    assert run(migrated.search_relevant_memories(
        principal_id="owner", query="legacy memory", limit=5
    ))[0].content == "legacy memory survives schema v3"
    assert run(migrated.reserve_idempotent_turn(
        principal_id="owner",
        conversation_id="legacy-chat",
        idempotency_key="00000000-0000-4000-8000-000000000125",
        user_message="new turn",
    )) is None
    database = sqlite3.connect(path)
    assert database.execute("PRAGMA user_version").fetchone()[0] == 4
    database.close()
