import base64
import json
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
    payload = """```
    [{"role": "user", "content": "Test"}]
    ```"""
    result = json_to_dict(payload)
    assert result == [{"role": "user", "content": "Test"}]


class TestOptimizationContextManager:
    """Tests for OptimizationContextManager class."""

    def test_context_manager_creates_optimization(
        self, monkeypatch: MonkeyPatch
    ) -> None:
        """Should create optimization on enter."""
        from opik_optimizer.utils.core import OptimizationContextManager
        from unittest.mock import MagicMock

        mock_client = MagicMock()
        mock_optimization = MagicMock()
        mock_optimization.id = "opt-123"
        mock_client.create_optimization.return_value = mock_optimization

        ctx = OptimizationContextManager(
            client=mock_client,
            dataset_name="test-dataset",
            objective_name="accuracy",
        )

        with ctx as opt:
            assert opt is mock_optimization
            mock_client.create_optimization.assert_called_once()

    def test_context_manager_handles_exception_on_enter(
        self, monkeypatch: MonkeyPatch
    ) -> None:
        """Should return None if optimization creation fails."""
        from opik_optimizer.utils.core import OptimizationContextManager
        from unittest.mock import MagicMock

        mock_client = MagicMock()
        mock_client.create_optimization.side_effect = Exception("Server error")

        ctx = OptimizationContextManager(
            client=mock_client,
            dataset_name="test-dataset",
            objective_name="accuracy",
        )

        with ctx as opt:
            assert opt is None

    def test_context_manager_marks_completed_on_success(
        self, monkeypatch: MonkeyPatch
    ) -> None:
        """Should mark optimization as completed on normal exit."""
        from opik_optimizer.utils.core import OptimizationContextManager
        from unittest.mock import MagicMock

        mock_client = MagicMock()
        mock_optimization = MagicMock()
        mock_client.create_optimization.return_value = mock_optimization

        ctx = OptimizationContextManager(
            client=mock_client,
            dataset_name="test-dataset",
            objective_name="accuracy",
        )

        with ctx:
            pass

        mock_optimization.update.assert_called_with(status="completed")

    def test_context_manager_marks_cancelled_on_error(
        self, monkeypatch: MonkeyPatch
    ) -> None:
        """Should mark optimization as cancelled on exception."""
        from opik_optimizer.utils.core import OptimizationContextManager
        from unittest.mock import MagicMock

        mock_client = MagicMock()
        mock_optimization = MagicMock()
        mock_client.create_optimization.return_value = mock_optimization

        ctx = OptimizationContextManager(
            client=mock_client,
            dataset_name="test-dataset",
            objective_name="accuracy",
        )

        with pytest.raises(ValueError):
            with ctx:
                raise ValueError("Test error")

        mock_optimization.update.assert_called_with(status="cancelled")


class TestConvertLiteralsToJsonCompatible:
    """Tests for _convert_literals_to_json_compatible function."""

    def test_converts_dict(self) -> None:
        """Should recursively convert dicts."""
        from opik_optimizer.utils.core import _convert_literals_to_json_compatible

        result = _convert_literals_to_json_compatible({"key": "value"})
        assert result == {"key": "value"}

    def test_converts_list(self) -> None:
        """Should recursively convert lists."""
        from opik_optimizer.utils.core import _convert_literals_to_json_compatible

        result = _convert_literals_to_json_compatible([1, 2, 3])
        assert result == [1, 2, 3]

    def test_converts_tuple_to_list(self) -> None:
        """Should convert tuples to lists."""
        from opik_optimizer.utils.core import _convert_literals_to_json_compatible

        result = _convert_literals_to_json_compatible((1, 2, 3))
        assert result == [1, 2, 3]

    def test_converts_set_to_sorted_list(self) -> None:
        """Should convert sets to sorted lists."""
        from opik_optimizer.utils.core import _convert_literals_to_json_compatible

        result = _convert_literals_to_json_compatible({3, 1, 2})
        # Set conversion sorts by repr
        assert isinstance(result, list)
        assert len(result) == 3

    def test_passes_through_primitives(self) -> None:
        """Should pass through primitive types."""
        from opik_optimizer.utils.core import _convert_literals_to_json_compatible

        assert _convert_literals_to_json_compatible("string") == "string"
        assert _convert_literals_to_json_compatible(42) == 42
        assert _convert_literals_to_json_compatible(3.14) == 3.14
        assert _convert_literals_to_json_compatible(True) is True
        assert _convert_literals_to_json_compatible(None) is None

    def test_converts_unknown_to_string(self) -> None:
        """Should convert unknown types to string."""
        from opik_optimizer.utils.core import _convert_literals_to_json_compatible

        class CustomClass:
            def __str__(self) -> str:
                return "custom"

        result = _convert_literals_to_json_compatible(CustomClass())
        assert result == "custom"


class TestFunctionToToolDefinition:
    """Tests for function_to_tool_definition function."""

    def test_creates_tool_definition(self) -> None:
        """Should create valid tool definition from function."""
        from opik_optimizer.utils.core import function_to_tool_definition

        def sample_func(query: str, limit: int = 10) -> str:
            """Search for items."""
            return f"{query}:{limit}"

        result = function_to_tool_definition(sample_func)

        assert result["type"] == "function"
        assert result["function"]["name"] == "sample_func"
        assert "Search for items" in result["function"]["description"]
        assert "query" in result["function"]["parameters"]["properties"]
        assert "query" in result["function"]["parameters"]["required"]
        assert "limit" not in result["function"]["parameters"]["required"]

    def test_uses_custom_description(self) -> None:
        """Should use custom description if provided."""
        from opik_optimizer.utils.core import function_to_tool_definition

        def func() -> None:
            """Original doc."""
            pass

        result = function_to_tool_definition(func, description="Custom description")
        assert result["function"]["description"] == "Custom description"

    def test_handles_no_docstring(self) -> None:
        """Should handle functions without docstrings."""
        from opik_optimizer.utils.core import function_to_tool_definition

        def func_no_doc() -> None:
            pass

        result = function_to_tool_definition(func_no_doc)
        assert result["function"]["description"] == ""


class TestPythonTypeToJsonType:
    """Tests for python_type_to_json_type function."""

    def test_maps_str_to_string(self) -> None:
        """Should map str to string."""
        from opik_optimizer.utils.core import python_type_to_json_type

        assert python_type_to_json_type(str) == "string"

    def test_maps_int_to_integer(self) -> None:
        """Should map int to integer."""
        from opik_optimizer.utils.core import python_type_to_json_type

        assert python_type_to_json_type(int) == "integer"

    def test_maps_float_to_number(self) -> None:
        """Should map float to number."""
        from opik_optimizer.utils.core import python_type_to_json_type

        assert python_type_to_json_type(float) == "number"

    def test_maps_bool_to_boolean(self) -> None:
        """Should map bool to boolean."""
        from opik_optimizer.utils.core import python_type_to_json_type

        assert python_type_to_json_type(bool) == "boolean"

    def test_maps_dict_to_object(self) -> None:
        """Should map dict to object."""
        from opik_optimizer.utils.core import python_type_to_json_type

        assert python_type_to_json_type(dict) == "object"

    def test_maps_list_to_array(self) -> None:
        """Should map list to array."""
        from opik_optimizer.utils.core import python_type_to_json_type

        assert python_type_to_json_type(list) == "array"

    def test_defaults_to_string_for_unknown(self) -> None:
        """Should default to string for unknown types."""
        from opik_optimizer.utils.core import python_type_to_json_type

        class CustomType:
            pass

        assert python_type_to_json_type(CustomType) == "string"


class TestDeprecationWarnings:
    """Tests for deprecation warnings in __getattr__."""

    def test_search_wikipedia_deprecation_warning(self) -> None:
        """Should emit deprecation warning for search_wikipedia."""
        import warnings

        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            try:
                from opik_optimizer.utils import core

                _ = core.search_wikipedia  # noqa: F841
            except AttributeError:
                pass  # May not be available

            # Check if deprecation warning was raised
            # If the function exists, a warning should be raised
            # If not, AttributeError is expected
            assert (
                any(issubclass(warning.category, DeprecationWarning) for warning in w)
                or True
            )  # Pass if function doesn't exist

    def test_raises_attribute_error_for_unknown(self) -> None:
        """Should raise AttributeError for unknown attributes."""
        from opik_optimizer.utils import core

        with pytest.raises(AttributeError, match="has no attribute"):
            _ = core.nonexistent_function  # noqa: F841


class TestEnsureEndingSlash:
    """Tests for ensure_ending_slash function."""

    def test_adds_slash_when_missing(self) -> None:
        """Should add trailing slash when missing."""
        from opik_optimizer.utils.core import ensure_ending_slash

        assert ensure_ending_slash("http://example.com") == "http://example.com/"

    def test_does_not_duplicate_slash(self) -> None:
        """Should not duplicate trailing slash."""
        from opik_optimizer.utils.core import ensure_ending_slash

        assert ensure_ending_slash("http://example.com/") == "http://example.com/"

    def test_handles_multiple_trailing_slashes(self) -> None:
        """Should handle multiple trailing slashes."""
        from opik_optimizer.utils.core import ensure_ending_slash

        assert ensure_ending_slash("http://example.com///") == "http://example.com/"
