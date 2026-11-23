from pathlib import Path

import pytest

DATA = Path(__file__).parent.parent / "src" / "opik_genui" / "data"


def pytest_configure(config):
    if not (DATA / "engine.json").is_file():
        raise pytest.UsageError(
            f"{DATA} is empty; run `npm run build` in sdks/genui, which writes the engine files there"
        )
