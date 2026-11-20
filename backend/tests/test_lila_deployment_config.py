import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest


BACKEND_ROOT = Path(__file__).resolve().parents[1]
LILA_DATA_PATH = "/var/lib/galaxy/lila"
LILA_DATABASE_PATH = f"{LILA_DATA_PATH}/lila.sqlite3"


def _resolved_compose_config():
    if shutil.which("docker") is None:
        pytest.skip("Docker Compose is unavailable in this environment")

    result = subprocess.run(
        ["docker", "compose", "config", "--format", "json"],
        cwd=BACKEND_ROOT,
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
    )
    if result.returncode != 0:
        pytest.fail("Docker Compose configuration validation failed")
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        pytest.fail("Docker Compose did not return valid JSON configuration")


def test_compose_mounts_lila_database_on_a_named_persistent_volume():
    config = _resolved_compose_config()
    backend = config["services"]["backend"]
    mounts = backend.get("volumes", [])

    assert backend["environment"]["LILA_DATABASE_PATH"] == LILA_DATABASE_PATH
    assert any(
        mount.get("type") == "volume"
        and mount.get("source") == "galaxy-lila-data"
        and mount.get("target") == LILA_DATA_PATH
        for mount in mounts
    )
    assert "galaxy-lila-data" in config["volumes"]


def test_compose_injects_storage_and_provider_secrets_from_runtime_environment():
    config = _resolved_compose_config()
    backend_environment = config["services"]["backend"]["environment"]
    storage_key = os.environ.get("LILA_STORAGE_ENCRYPTION_KEY")

    assert storage_key is not None and len(storage_key) == 64
    assert backend_environment["LILA_STORAGE_ENCRYPTION_KEY"] == storage_key
    assert "LILA_PROVIDER_API_KEY" in backend_environment


def test_backend_image_prepares_private_store_directory_for_runtime_uid():
    dockerfile = (BACKEND_ROOT / "Dockerfile").read_text(encoding="utf-8")

    assert "install -d -o galaxy -g galaxy -m 0700 /var/lib/galaxy/lila" in dockerfile
    assert "USER 10001:10001" in dockerfile


def test_env_example_does_not_store_lila_secrets():
    env_text = (BACKEND_ROOT / ".env.example").read_text(encoding="utf-8")
    values = {}
    for raw_line in env_text.splitlines():
        line = raw_line.strip()
        if line and not line.startswith("#") and "=" in line:
            name, value = line.split("=", 1)
            values[name] = value

    assert values["LILA_PROVIDER_BASE_URL"] == ""
    assert values["LILA_MODEL_ID"] == ""
    assert "LILA_PROVIDER_API_KEY" not in values
    assert "LILA_STORAGE_ENCRYPTION_KEY" not in values
    assert values["LILA_DATABASE_PATH"] == LILA_DATABASE_PATH
    assert "host secret manager" in env_text
