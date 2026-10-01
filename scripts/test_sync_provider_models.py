import pytest

import sync_provider_models as spm


@pytest.mark.parametrize(
    "total_added, seeded_reasoning_ids, cleared_reasoning_ids, force_regen, fell_back, expected",
    [
        (1, [], [], False, True, False),
        (0, ["o3"], [], False, True, False),
        (0, [], ["gpt-5-chat-latest"], False, True, False),
        (0, [], [], False, False, False),
        (1, [], [], False, False, True),
        (0, ["o3"], [], False, False, True),
        (0, [], ["gpt-5-chat-latest"], False, False, True),
        (1, [], [], True, True, True),
        (0, [], [], True, False, True),
    ],
    ids=[
        "fallback_with_additions",
        "fallback_with_seeded_reasoning",
        "fallback_with_cleared_reasoning",
        "no_changes",
        "additions_without_fallback",
        "seeded_reasoning_without_fallback",
        "cleared_reasoning_without_fallback",
        "force_regen_overrides_fallback",
        "force_regen_without_changes",
    ],
)
def test_should_write_files__case__expected(
    total_added, seeded_reasoning_ids, cleared_reasoning_ids, force_regen, fell_back, expected
):
    assert (
        spm._should_write_files(
            total_added, seeded_reasoning_ids, cleared_reasoning_ids, force_regen, fell_back
        )
        is expected
    )


def _openrouter_fails():
    raise RuntimeError("OpenRouter is down")


@pytest.mark.parametrize(
    "fetch_openrouter, expected_fell_back",
    [(_openrouter_fails, True), (lambda: [], False)],
    ids=["fetch_fails", "fetch_succeeds"],
)
def test_main__openrouter_fetch__sets_fell_back_only_on_failure(
    monkeypatch, fetch_openrouter, expected_fell_back
):
    for key in ("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GEMINI_API_KEY"):
        monkeypatch.delenv(key, raising=False)
    monkeypatch.setattr(spm, "fetch_openrouter_models", fetch_openrouter)
    monkeypatch.setattr(spm, "write_file", lambda *_: pytest.fail("main must not write files"))
    publish_calls = []
    monkeypatch.setattr(
        spm, "_should_write_files", lambda *args: publish_calls.append(args) or False
    )
    monkeypatch.setattr("sys.argv", ["sync_provider_models.py", "--dry-run"])

    with pytest.raises(SystemExit):
        spm.main()

    assert len(publish_calls) == 1
    *_, fell_back = publish_calls[0]
    assert fell_back is expected_fell_back


def _openai_entry(model_id):
    return spm.ModelEntry(enum_name=model_id.upper(), value=model_id, structured_output=False, label=model_id)


def _reasoning_ids(yaml_content):
    return set(spm._parse_yaml_reasoning_flags(yaml_content).get("openai", {}))


def test_regenerate_llm_models_yaml__excluded_model_flagged_in_existing_file__flag_dropped():
    existing = (
        "openai:\n"
        '  - id: "gpt-5-chat-latest"\n'
        "    reasoning: true\n"
        '  - id: "o3"\n'
        "    reasoning: true\n"
    )
    entries = [_openai_entry("gpt-5-chat-latest"), _openai_entry("o3")]

    regenerated = spm.regenerate_llm_models_yaml(
        existing,
        {"openai": entries},
        openai_reasoning={"gpt-5-chat-latest": True, "o3": True},
    )

    assert _reasoning_ids(regenerated) == {"o3"}


def test_regenerate_llm_models_yaml__litellm_marks_excluded_and_reasoning_models__only_reasoning_seeded():
    entries = [_openai_entry("gpt-5.2-chat-latest"), _openai_entry("o4-mini"), _openai_entry("gpt-4o")]

    regenerated = spm.regenerate_llm_models_yaml(
        "openai:\n  []\n",
        {"openai": entries},
        openai_reasoning={"gpt-5.2-chat-latest": True, "o4-mini": True, "gpt-4o": False},
    )

    assert _reasoning_ids(regenerated) == {"o4-mini"}


def _existing_yaml_with_stale_flag():
    model_line = '  - id: "gpt-5-chat-latest"\n'
    existing = spm.read_file(spm.LLM_MODELS_YAML)
    assert existing.count(model_line) == 1
    return existing.replace(model_line, model_line + "    reasoning: true\n")


@pytest.mark.parametrize(
    "existing_yaml, expected_written",
    [(_existing_yaml_with_stale_flag, True), (lambda: spm.read_file(spm.LLM_MODELS_YAML), False)],
    ids=["stale_flag_in_existing_yaml", "no_stale_flag"],
)
def test_main__only_change_is_a_cleared_stale_flag__yaml_written(monkeypatch, existing_yaml, expected_written):
    for key in ("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GEMINI_API_KEY"):
        monkeypatch.delenv(key, raising=False)
    existing = existing_yaml()
    real_read_file = spm.read_file
    monkeypatch.setattr(
        spm, "read_file", lambda path: existing if path == spm.LLM_MODELS_YAML else real_read_file(path)
    )
    # With no prices and an empty OpenRouter list no provider adds a model, so the stale flag is the only change.
    monkeypatch.setattr(spm, "load_model_prices", lambda: {})
    monkeypatch.setattr(spm, "fetch_openrouter_models", lambda: [])
    written = {}
    monkeypatch.setattr(spm, "write_file", written.__setitem__)
    monkeypatch.setattr("sys.argv", ["sync_provider_models.py"])

    with pytest.raises(SystemExit):
        spm.main()

    assert (spm.LLM_MODELS_YAML in written) is expected_written
    if expected_written:
        assert "gpt-5-chat-latest" not in _reasoning_ids(written[spm.LLM_MODELS_YAML])
