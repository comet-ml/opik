import base64
import pytest
from pytest import MonkeyPatch

from opik_optimizer.utils import (
    format_prompt,
    json_to_dict,
    validate_prompt,
    get_random_seed,
    setup_logging,
    get_optimization_run_url_by_id,
)


def test_format_prompt() -> None:
    """Test the format_prompt function."""
    # Test basic formatting
    prompt = "Hello {name}!"
    result = format_prompt(prompt, name="World")
    assert result == "Hello World!"

    # Test with multiple variables
    prompt = "{greeting} {name}!"
    result = format_prompt(prompt, greeting="Hi", name="World")
    assert result == "Hi World!"

    # Test with missing variable
    with pytest.raises(ValueError) as exc_info:
        format_prompt(prompt, greeting="Hi")
    assert "Missing required key in prompt: 'name'" in str(exc_info.value)


def test_validate_prompt() -> None:
    # Test valid prompt
    assert validate_prompt("Hello World!") is True

    # Test empty prompt
    assert validate_prompt("") is False

    # Test prompt with only whitespace
    assert validate_prompt("   ") is False

    # Test prompt with newlines
    assert validate_prompt("Hello\nWorld") is True


def test_get_random_seed() -> None:
    # Test that seed is an integer
    seed = get_random_seed()
    assert isinstance(seed, int)

    # Test that seed is within reasonable range
    assert 0 <= seed <= 2**32 - 1

    # Test that seed is different on subsequent calls
    seed1 = get_random_seed()
    seed2 = get_random_seed()
    assert seed1 != seed2


def test_setup_logging() -> None:
    # Test that setup_logging doesn't raise any errors
    setup_logging()

    # Test with custom log level
    setup_logging(log_level="DEBUG")

    # Test with invalid log level
    with pytest.raises(ValueError):
        setup_logging(log_level="INVALID")


def test_get_optimization_run_url_by_id(monkeypatch: MonkeyPatch) -> None:
    """Test get_optimization_run_url_by_id with environment variable set only for this test."""
    URL_OVERRIDE = "https://URL/opik/api"
    ENCODED_URL = base64.b64encode(URL_OVERRIDE.encode("utf-8")).decode("utf-8")
    OPTIMIZATION_ID = "OPTIMIZATION-ID"
    DATASET_ID = "DATASET-ID"

    # Set the environment variable only for this test
    monkeypatch.setenv("OPIK_URL_OVERRIDE", URL_OVERRIDE)

    url = get_optimization_run_url_by_id(
        dataset_id=DATASET_ID, optimization_id=OPTIMIZATION_ID
    )

    assert (
        url
        == f"{URL_OVERRIDE}/v1/session/redirect/optimizations/?optimization_id={OPTIMIZATION_ID}&dataset_id={DATASET_ID}&path={ENCODED_URL}"
    )


def test_json_to_dict_parses_raw_json_list() -> None:
    """Ensure json_to_dict handles plain JSON arrays of chat messages."""
    payload = """[
        {"role": "system", "content": "Be concise."},
        {"role": "user", "content": "Question"}
    ]"""

    result = json_to_dict(payload)

    assert result == [
        {"role": "system", "content": "Be concise."},
        {"role": "user", "content": "Question"},
    ]


def test_json_to_dict_strips_json_code_block() -> None:
    """Ensure json_to_dict trims ```json fenced responses before parsing."""
    payload = """```json
    [
        {"role": "assistant", "content": "Sure"}
    ]
    ```"""

    result = json_to_dict(payload)

    assert result == [{"role": "assistant", "content": "Sure"}]


def test_json_to_dict_handles_python_literal(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Ensure python-style reprs are parsed via literal_eval fallback."""
    payload = """[{'role': 'system', 'content': 'Do not forget to cite sources.'}]"""

    result = json_to_dict(payload)

    assert result == [{"role": "system", "content": "Do not forget to cite sources."}]

    captured = capsys.readouterr()
    assert captured.out == ""
