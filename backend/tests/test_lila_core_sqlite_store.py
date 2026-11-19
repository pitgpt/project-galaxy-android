import asyncio
import sqlite3
import stat

import pytest

from galaxy_backend.lila_core.memory import MemorySnippet
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
        memory=MemorySnippet(memory_id="private-memory-id", content="private memory phrase"),
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
