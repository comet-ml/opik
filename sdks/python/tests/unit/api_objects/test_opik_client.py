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


@pytest.mark.parametrize(
    "experiment_id",
    [
        None,
        "",
    ],
)
def test_opik_client__update_experiment__missing_mandatory_parameters__error_raised(
    experiment_id,
):
    opik_client_ = opik_client.Opik()

    with pytest.raises(ValueError):
        opik_client_.update_experiment(id=experiment_id)
