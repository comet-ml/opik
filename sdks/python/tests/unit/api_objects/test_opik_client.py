import pytest

from opik.api_objects import opik_client


@pytest.mark.parametrize(
    "trace_id,project_name",
    [
        (None, "some-project"),
        ("some-trace-id", None),
        (None, None),
        ("", "some-project"),
        ("some-trace-id", ""),
        ("", ""),
    ],
)
def test_opik_client__update_trace__missing_mandatory_parameters__error_raised(
    trace_id, project_name
):
    opik_client_ = opik_client.Opik()

    with pytest.raises(ValueError):
        opik_client_.update_trace(trace_id=trace_id, project_name=project_name)
