import os

import pytest


@pytest.fixture(autouse=True)
def isolated_opik_home(tmp_path, monkeypatch):
    home = str(tmp_path / ".opik")
    monkeypatch.setenv("OPIK_HOME", home)
    os.makedirs(home, exist_ok=True)
    return home
