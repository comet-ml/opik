import base64
import json
import pytest
from pytest import MonkeyPatch

from opik_optimizer.utils.logging import setup_logging
from opik_optimizer.utils.helpers import json_to_dict
from opik_optimizer.utils.reporting import get_optimization_run_url_by_id
from tests.unit.fixtures import assistant_message, system_message


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


class TestFunctionToToolDefinition:
    """Legacy function_to_tool_definition tests removed with deprecated API."""


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
