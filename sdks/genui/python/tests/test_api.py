import json
from pathlib import Path

import pytest

import opik_genui

DATA = Path(__file__).parent.parent / "src" / "opik_genui" / "data"


def test_constants__match_engine_json():
    engine = json.loads((DATA / "engine.json").read_text())
    assert opik_genui.ENGINE_VERSION == engine["engine_version"]
    assert isinstance(opik_genui.ENGINE_VERSION, int)
    assert opik_genui.AREAS == ("widget",)
    assert opik_genui.LIBRARIES == ("vega",)
    assert opik_genui.MAX_HTML_LENGTH == engine["max_html_length"]
    assert opik_genui.TOKENS == engine["tokens"]


def test_tokens__light_and_dark_define_the_same_names():
    assert set(opik_genui.TOKENS["light"]) == set(opik_genui.TOKENS["dark"])


def test_prompt__returns_the_emitted_prompt_per_area():
    for area in opik_genui.AREAS:
        assert opik_genui.prompt(area) == (
            DATA / f"prompt-{area}.txt"
        ).read_text().removesuffix("\n")


def test_prompt__lists_every_token_and_the_size_cap():
    text = opik_genui.prompt("widget")
    assert all(name in text for name in opik_genui.TOKENS["light"])
    assert f"{opik_genui.MAX_HTML_LENGTH:,} characters" in text


def test_prompt__unknown_area__raises():
    with pytest.raises(ValueError, match='unknown area "dashboard"'):
        opik_genui.prompt("dashboard")
