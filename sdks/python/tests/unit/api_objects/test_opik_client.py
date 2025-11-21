import pytest
from unittest.mock import patch

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


def test_opik_client__update_experiment__both_name_and_config__both_sent_to_api():
    opik_client_ = opik_client.Opik()

    with patch.object(
            opik_client_._rest_client.experiments, "update_experiment"
    ) as mock_update:
        new_config = {"model": "gpt-4", "temperature": 0.7}
        opik_client_.update_experiment(
            id="some-experiment-id", name="new-name", experiment_config=new_config
        )

        mock_update.assert_called_once()
        call_kwargs = mock_update.call_args[1]
        assert call_kwargs["name"] == "new-name"
        assert call_kwargs["metadata"] == new_config


def test_opik_client__update_experiment__name_only__only_name_sent_to_api():
    opik_client_ = opik_client.Opik()

    with patch.object(
            opik_client_._rest_client.experiments, "update_experiment"
    ) as mock_update:
        opik_client_.update_experiment(id="some-experiment-id", name="new-name")

        mock_update.assert_called_once()
        call_kwargs = mock_update.call_args[1]
        assert call_kwargs["name"] == "new-name"
        assert "metadata" not in call_kwargs


def test_opik_client__update_experiment__config_only__only_metadata_sent_to_api():
    opik_client_ = opik_client.Opik()

    with patch.object(
            opik_client_._rest_client.experiments, "update_experiment"
    ) as mock_update:
        new_config = {"model": "gpt-4", "temperature": 0.7}
        opik_client_.update_experiment(
            id="some-experiment-id", experiment_config=new_config
        )

        mock_update.assert_called_once()
        call_kwargs = mock_update.call_args[1]
        assert "name" not in call_kwargs
        assert call_kwargs["metadata"] == new_config


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


def test_opik_client__update_experiment__no_update_parameters__error_raised():
    opik_client_ = opik_client.Opik()

    with pytest.raises(ValueError):
        opik_client_.update_experiment(id="some-experiment-id")
