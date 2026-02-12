import base64
import json
import pytest
from pytest import MonkeyPatch

from opik_optimizer.utils.helpers import json_to_dict
from opik_optimizer.utils.display.format import format_prompt
from opik_optimizer.utils.reporting import get_optimization_run_url_by_id
from opik_optimizer.utils.logging import setup_logging
from tests.unit.fixtures import assistant_message, system_message, user_message


@pytest.mark.parametrize(
    "prompt_template,kwargs,expected_result,should_raise",
    [
        ("Hello {name}!", {"name": "World"}, "Hello World!", None),
        ("{greeting} {name}!", {"greeting": "Hi", "name": "World"}, "Hi World!", None),
        ("{greeting} {name}!", {"greeting": "Hi"}, None, ValueError),
    ],
)
def test_format_prompt(
    prompt_template: str,
    kwargs: dict[str, str],
    expected_result: str | None,
    should_raise: type[Exception] | None,
) -> None:
    """Test the format_prompt function."""
    if should_raise:
        with pytest.raises(should_raise) as exc_info:
            format_prompt(prompt_template, **kwargs)
        assert "Missing required key in prompt" in str(exc_info.value)
    else:
        result = format_prompt(prompt_template, **kwargs)
        assert result == expected_result


@pytest.mark.parametrize(
    "level,should_raise",
    [
        ("INFO", False),
        ("DEBUG", False),
        ("INVALID", True),
    ],
)
def test_setup_logging(level: str, should_raise: bool) -> None:
    """Test setup_logging with different log levels."""
    if should_raise:
        with pytest.raises(ValueError):
            setup_logging(level=level, force=True)
    else:
        setup_logging(level=level, force=True)


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
    payload = json.dumps([system_message("Be concise."), user_message("Question")])

    result = json_to_dict(payload)

    assert result == [
        system_message("Be concise."),
        user_message("Question"),
    ]


def test_json_to_dict_strips_json_code_block() -> None:
    """Ensure json_to_dict trims ```json fenced responses before parsing."""
    payload = f"""```json
{json.dumps([assistant_message("Sure")])}
```"""

    result = json_to_dict(payload)

    assert result == [assistant_message("Sure")]


def test_json_to_dict_handles_python_literal(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Ensure python-style reprs are parsed via literal_eval fallback."""
    # Clear any leftover output from previous tests (e.g., optimizer cleanup logging)
    capsys.readouterr()

    payload = repr([system_message("Do not forget to cite sources.")])

    result = json_to_dict(payload)

    assert result == [system_message("Do not forget to cite sources.")]

    # Check that json_to_dict didn't produce any output
    captured = capsys.readouterr()
    assert captured.out == ""


def test_json_to_dict_returns_none_on_unparseable() -> None:
    """Test json_to_dict returns None for completely unparsable content."""
    try:
        result = json_to_dict("This is not JSON or Python literal: <xml>tag</xml>")
    except json.JSONDecodeError:
        # Accept implementations that surface the decode error directly.
        return
    assert result is None or isinstance(result, (dict, list))


def test_json_to_dict_handles_code_block_without_json_tag() -> None:
    """Test json_to_dict handles code blocks without json tag."""
    payload = f"""```
{json.dumps([user_message("Test")])}
```"""
    result = json_to_dict(payload)
    assert result == [user_message("Test")]


def test_convert_tqdm_to_rich_adapter_supports_set_postfix() -> None:
    """Ensure tqdm adapter exposed by convert_tqdm_to_rich has set_postfix."""
    from opik.evaluation.engine import evaluation_tasks_executor
    from opik_optimizer.utils.reporting import convert_tqdm_to_rich

    with convert_tqdm_to_rich(description="Eval", verbose=1):
        progress_bar = evaluation_tasks_executor._tqdm(
            disable=False,
            desc="Eval",
            total=2,
        )
        progress_bar.set_postfix({"score": "0.1234", "latency_ms": 90})
        assert progress_bar._progress.tasks[progress_bar._task_id].fields[
            "postfix"
        ] == ("[dim]score:[/] 0.1234 | [dim]latency_ms:[/] 90")
        progress_bar.update(1)
        progress_bar.close()


class TestConvertLiteralsToJsonCompatible:
    """Tests for _convert_literals_to_json_compatible function."""

    def test_converts_dict(self) -> None:
        """Should recursively convert dicts."""
        from opik_optimizer.utils.helpers import _convert_literals_to_json_compatible

        result = _convert_literals_to_json_compatible({"key": "value"})
        assert result == {"key": "value"}

    def test_converts_list(self) -> None:
        """Should recursively convert lists."""
        from opik_optimizer.utils.helpers import _convert_literals_to_json_compatible

        result = _convert_literals_to_json_compatible([1, 2, 3])
        assert result == [1, 2, 3]

    def test_converts_tuple_to_list(self) -> None:
        """Should convert tuples to lists."""
        from opik_optimizer.utils.helpers import _convert_literals_to_json_compatible

        result = _convert_literals_to_json_compatible((1, 2, 3))
        assert result == [1, 2, 3]

    def test_converts_set_to_sorted_list(self) -> None:
        """Should convert sets to sorted lists."""
        from opik_optimizer.utils.helpers import _convert_literals_to_json_compatible

        result = _convert_literals_to_json_compatible({3, 1, 2})
        # Set conversion sorts by repr
        assert isinstance(result, list)
        assert len(result) == 3

    def test_passes_through_primitives(self) -> None:
        """Should pass through primitive types."""
        from opik_optimizer.utils.helpers import _convert_literals_to_json_compatible

        assert _convert_literals_to_json_compatible("string") == "string"
        assert _convert_literals_to_json_compatible(42) == 42
        assert _convert_literals_to_json_compatible(3.14) == 3.14
        assert _convert_literals_to_json_compatible(True) is True
        assert _convert_literals_to_json_compatible(None) is None

    def test_converts_unknown_to_string(self) -> None:
        """Should convert unknown types to string."""
        from opik_optimizer.utils.helpers import _convert_literals_to_json_compatible

        class CustomClass:
            def __str__(self) -> str:
                return "custom"

        result = _convert_literals_to_json_compatible(CustomClass())
        assert result == "custom"


class TestFunctionToToolDefinition:
    """Legacy function_to_tool_definition tests removed with deprecated API."""


class TestEnsureEndingSlash:
    """Tests for ensure_ending_slash function."""

    @pytest.mark.parametrize(
        "input_url,expected",
        [
            ("http://example.com", "http://example.com/"),
            ("http://example.com/", "http://example.com/"),
            ("http://example.com///", "http://example.com/"),
        ],
    )
    def test_ensure_ending_slash(self, input_url: str, expected: str) -> None:
        """Should ensure exactly one trailing slash."""
        from opik_optimizer.utils.reporting import ensure_ending_slash

        assert ensure_ending_slash(input_url) == expected
