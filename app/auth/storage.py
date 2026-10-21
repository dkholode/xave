"""Secure persistence for Playwright authentication state."""
from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path


def write_storage_state(path: Path, state: dict) -> None:
    """Atomically write credential-bearing browser state with owner-only access."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=path.parent,
            prefix=f".{path.name}.",
            delete=False,
        ) as stream:
            temporary_path = Path(stream.name)
            os.chmod(temporary_path, 0o600)
            json.dump(state, stream, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_path, path)
        os.chmod(path, 0o600)
    except Exception:
        if temporary_path and temporary_path.exists():
            temporary_path.unlink()
        raise
