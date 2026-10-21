from app.auth import login


def test_cancelled_login_confirmation_exits_cleanly(monkeypatch, capsys):
    def cancel(prompt):
        raise KeyboardInterrupt

    monkeypatch.setattr("builtins.input", cancel)

    assert login._wait_for_confirmation() is False
    assert "app.auth.from_cookies" in capsys.readouterr().err
