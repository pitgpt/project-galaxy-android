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
    assert "host.docker.internal=host-gateway" in backend.get("extra_hosts", [])


def test_compose_mounts_storage_and_provider_secrets_without_container_environment_values():
    config = _resolved_compose_config()
    backend = config["services"]["backend"]
    backend_environment = backend["environment"]
    storage_key = os.environ.get("LILA_STORAGE_ENCRYPTION_KEY")
    provider_key = os.environ.get("LILA_PROVIDER_API_KEY", "")

    assert storage_key is not None and len(storage_key) == 64
    assert backend_environment["LILA_STORAGE_ENCRYPTION_KEY"] == ""
    assert backend_environment["LILA_PROVIDER_API_KEY"] == ""
    assert backend_environment["LILA_STORAGE_ENCRYPTION_KEY_FILE"] == "/run/secrets/lila_storage_encryption_key"
    assert backend_environment["LILA_PROVIDER_API_KEY_FILE"] == "/run/secrets/lila_provider_api_key"
    mounted = {
        item if isinstance(item, str) else item["source"]
        for item in backend["secrets"]
    }
    assert mounted == {"lila_storage_encryption_key", "lila_provider_api_key"}
    assert config["secrets"]["lila_storage_encryption_key"]["environment"] == "LILA_STORAGE_ENCRYPTION_KEY"
    assert config["secrets"]["lila_provider_api_key"]["environment"] == "LILA_PROVIDER_API_KEY"
    resolved = json.dumps(config)
    assert storage_key not in resolved
    if provider_key:
        assert provider_key not in resolved


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
    assert values["LILA_PROVIDER_API_KEY"] == ""
    assert values["LILA_STORAGE_ENCRYPTION_KEY"] == ""
    assert values["LILA_DATABASE_PATH"] == LILA_DATABASE_PATH
    assert values["AUTH_JWKS_URL"] == "https://YOUR_PROJECT_REF.supabase.co/auth/v1/.well-known/jwks.json"
    assert values["AUTH_ISSUER"] == "https://YOUR_PROJECT_REF.supabase.co/auth/v1"
    assert values["AUTH_AUDIENCE"] == "authenticated"
    assert "asymmetric RS256/ES256" in env_text
    assert "host secret manager" in env_text



def test_encrypted_storage_can_start_provider_catalog_without_operator_model(monkeypatch, tmp_path):
    from galaxy_backend.config import Settings

    monkeypatch.setenv("AUTH_JWKS_URL", "https://issuer.example/.well-known/jwks.json")
    monkeypatch.setenv("AUTH_ISSUER", "https://issuer.example/")
    monkeypatch.setenv("AUTH_AUDIENCE", "galaxy-android")
    monkeypatch.setenv("GALAXY_ENVIRONMENT", "development")
    monkeypatch.setenv("LILA_DATABASE_PATH", str(tmp_path / "lila.sqlite3"))
    monkeypatch.setenv("LILA_STORAGE_ENCRYPTION_KEY", "01" * 32)
    monkeypatch.delenv("LILA_PROVIDER_BASE_URL", raising=False)
    monkeypatch.delenv("LILA_MODEL_ID", raising=False)
    monkeypatch.delenv("LILA_PROVIDER_API_KEY", raising=False)

    settings = Settings.from_env()

    assert settings.lila_storage_is_configured is True
    assert settings.lila_provider_is_configured is False
    assert settings.lila_is_configured is False
    assert settings.lila_configuration_requested is True


def test_settings_load_secret_files_without_exposing_values_in_repr(monkeypatch, tmp_path):
    from galaxy_backend.config import Settings

    storage_key = "ab" * 32
    provider_key = "provider-file-secret"
    storage_file = tmp_path / "storage-key"
    provider_file = tmp_path / "provider-key"
    storage_file.write_text(storage_key + "\n", encoding="ascii")
    provider_file.write_text(provider_key + "\n", encoding="ascii")

    monkeypatch.setenv("AUTH_JWKS_URL", "https://issuer.example/.well-known/jwks.json")
    monkeypatch.setenv("AUTH_ISSUER", "https://issuer.example/")
    monkeypatch.setenv("AUTH_AUDIENCE", "galaxy-android")
    monkeypatch.setenv("GALAXY_ENVIRONMENT", "development")
    monkeypatch.setenv("LILA_DATABASE_PATH", str(tmp_path / "lila.sqlite3"))
    monkeypatch.delenv("LILA_STORAGE_ENCRYPTION_KEY", raising=False)
    monkeypatch.setenv("LILA_STORAGE_ENCRYPTION_KEY_FILE", str(storage_file))
    monkeypatch.delenv("LILA_PROVIDER_API_KEY", raising=False)
    monkeypatch.setenv("LILA_PROVIDER_API_KEY_FILE", str(provider_file))

    settings = Settings.from_env()

    assert settings.lila_storage_encryption_key == bytes.fromhex(storage_key)
    assert settings.lila_provider_api_key == provider_key
    assert storage_key not in repr(settings)
    assert provider_key not in repr(settings)


def test_settings_reject_secret_value_and_secret_file_ambiguity(monkeypatch, tmp_path):
    from galaxy_backend.config import Settings

    monkeypatch.setenv("AUTH_JWKS_URL", "https://issuer.example/.well-known/jwks.json")
    monkeypatch.setenv("AUTH_ISSUER", "https://issuer.example/")
    monkeypatch.setenv("AUTH_AUDIENCE", "galaxy-android")
    monkeypatch.setenv("GALAXY_ENVIRONMENT", "development")
    monkeypatch.setenv("LILA_DATABASE_PATH", str(tmp_path / "lila.sqlite3"))
    monkeypatch.setenv("LILA_STORAGE_ENCRYPTION_KEY", "01" * 32)
    secret_file = tmp_path / "storage-key"
    secret_file.write_text("02" * 32, encoding="ascii")
    monkeypatch.setenv("LILA_STORAGE_ENCRYPTION_KEY_FILE", str(secret_file))

    with pytest.raises(RuntimeError, match="Set only one of LILA_STORAGE_ENCRYPTION_KEY"):
        Settings.from_env()


def test_settings_fail_closed_when_secret_file_is_missing(monkeypatch, tmp_path):
    from galaxy_backend.config import Settings

    monkeypatch.setenv("AUTH_JWKS_URL", "https://issuer.example/.well-known/jwks.json")
    monkeypatch.setenv("AUTH_ISSUER", "https://issuer.example/")
    monkeypatch.setenv("AUTH_AUDIENCE", "galaxy-android")
    monkeypatch.setenv("GALAXY_ENVIRONMENT", "development")
    monkeypatch.setenv("LILA_DATABASE_PATH", str(tmp_path / "lila.sqlite3"))
    monkeypatch.setenv("LILA_STORAGE_ENCRYPTION_KEY_FILE", str(tmp_path / "missing-secret"))

    with pytest.raises(RuntimeError, match="LILA_STORAGE_ENCRYPTION_KEY_FILE could not be read"):
        Settings.from_env()


def test_operator_provider_requires_encrypted_storage(monkeypatch):
    from galaxy_backend.config import Settings

    monkeypatch.setenv("AUTH_JWKS_URL", "https://issuer.example/.well-known/jwks.json")
    monkeypatch.setenv("AUTH_ISSUER", "https://issuer.example/")
    monkeypatch.setenv("AUTH_AUDIENCE", "galaxy-android")
    monkeypatch.setenv("GALAXY_ENVIRONMENT", "development")
    monkeypatch.setenv("LILA_PROVIDER_BASE_URL", "https://api.vendor.com/v1")
    monkeypatch.setenv("LILA_MODEL_ID", "model-a")
    monkeypatch.delenv("LILA_DATABASE_PATH", raising=False)
    monkeypatch.delenv("LILA_STORAGE_ENCRYPTION_KEY", raising=False)

    with pytest.raises(RuntimeError, match="Lila configuration is incomplete"):
        Settings.from_env()
