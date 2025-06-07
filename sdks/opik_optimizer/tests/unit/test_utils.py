import pytest
import base64
import io

from opik_optimizer.utils import (
    format_prompt,
    validate_prompt,
    get_random_seed,
    setup_logging,
    get_optimization_run_url_by_id,
    display_optimization_run_link,
)

from unittest.mock import patch


def test_format_prompt():
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


def test_validate_prompt():
    # Test valid prompt
    assert validate_prompt("Hello World!") is True

    # Test empty prompt
    assert validate_prompt("") is False

    # Test prompt with only whitespace
    assert validate_prompt("   ") is False

    # Test prompt with newlines
    assert validate_prompt("Hello\nWorld") is True


def test_get_random_seed():
    # Test that seed is an integer
    seed = get_random_seed()
    assert isinstance(seed, int)

    # Test that seed is within reasonable range
    assert 0 <= seed <= 2**32 - 1

    # Test that seed is different on subsequent calls
    seed1 = get_random_seed()
    seed2 = get_random_seed()
    assert seed1 != seed2


def test_setup_logging():
    # Test that setup_logging doesn't raise any errors
    setup_logging()

    # Test with custom log level
    setup_logging(log_level="DEBUG")

    # Test with invalid log level
    with pytest.raises(ValueError):
        setup_logging(log_level="INVALID")


def test_get_optimization_run_url_by_id():
    URL_OVERRIDE = "https://URL/opik/api"
    ENCODED_URL = base64.b64encode(URL_OVERRIDE.encode("utf-8")).decode("utf-8")
    OPTIMIZATION_ID = "OPTIMIZATION-ID"
    DATASET_ID = "DATASET-ID"

    url = get_optimization_run_url_by_id(
        dataset_id=DATASET_ID,
        optimization_id=OPTIMIZATION_ID,
        url_override=URL_OVERRIDE,
    )

    assert (
        url
        == f"{URL_OVERRIDE}/v1/session/redirect/optimizations/?optimization_id={OPTIMIZATION_ID}&dataset_id={DATASET_ID}&path={ENCODED_URL}"
    )


@patch("sys.stdout", new_callable=io.StringIO)
def test_display_optimization_run_link(mock_stdout):
    URL_OVERRIDE = "https://URL/opik/api"
    OPTIMIZATION_ID = "OPTIMIZATION-ID"
    DATASET_ID = "DATASET-ID"

    display_optimization_run_link(
        dataset_id=DATASET_ID,
        optimization_id=OPTIMIZATION_ID,
        url_override=URL_OVERRIDE,
    )

    output = mock_stdout.getvalue().strip()

    assert output == "View the optimization run in your Opik dashboard."
