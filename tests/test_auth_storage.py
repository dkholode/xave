import json
import stat

from app.auth import from_cookies
from app.auth.storage import write_storage_state


def test_write_storage_state_restricts_permissions(tmp_path):
    path = tmp_path / "storage_state.json"

    write_storage_state(path, {"cookies": [{"name": "test"}], "origins": []})

    assert json.loads(path.read_text())["cookies"][0]["name"] == "test"
    assert stat.S_IMODE(path.stat().st_mode) == 0o600


def test_cookie_import_hides_both_prompts_and_writes_securely(tmp_path, monkeypatch):
    path = tmp_path / "storage_state.json"
    answers = iter(["secret-auth", "secret-ct0"])
    prompts = []

    def hidden_prompt(prompt):
        prompts.append(prompt)
        return next(answers)

    monkeypatch.setattr(from_cookies.config, "STORAGE_STATE", path)
    monkeypatch.delenv("XAVE_AUTH_TOKEN", raising=False)
    monkeypatch.delenv("XAVE_CT0", raising=False)
    monkeypatch.setattr(from_cookies.getpass, "getpass", hidden_prompt)

    assert from_cookies.main([]) == 0
    assert len(prompts) == 2
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
