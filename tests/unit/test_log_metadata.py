import pytest
from testix import *

from comet_llm.api_objects import llm_trace_api


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(llm_trace_api, "io")
    patch_module(llm_trace_api, "json")
    patch_module(llm_trace_api.LLMTraceAPI, "_get_trace_data")


def test_log_metadata__override_true(mock_imports):
    old_params = [
        {
            'name': 'batch_size',
            'valueMax': '120',
            'valueMin': '120',
            'valueCurrent': '120',
            'timestampMax': 1558962363411,
            'timestampMin': 1558962363411,
            'timestampCurrent': 1558962363411,
        },
        {
            'name': 'step',
            'valueMax': 1,
            'valueMin': 2,
            'valueCurrent': 1,
            'timestampMax': 1558962363411,
            'timestampMin': 1558962363411,
            'timestampCurrent': 1558962363411,
        },
    ]
    old_params_names = [p['name'] for p in old_params]
    json_stream = Fake("stream")
    metadata = {"metadata-key": "metadata-value"}
    trace_data = {
        "metadata": {
            "old--key": "old-value",
        },
    }
    expected_trace_data = {
        "metadata": {
            "metadata-key": "metadata-value",
        },
    }
    experiment = Fake("_api_experiment")
    llm_trace = llm_trace_api.LLMTraceAPI.__api__from_api_experiment__(experiment)

    with Scenario() as s:
        s._get_trace_data() >> trace_data
        s._api_experiment.get_parameters_summary() >> old_params
        s._api_experiment.delete_parameters(old_params_names)
        s.io.StringIO() >> json_stream
        s.json.dump(expected_trace_data, json_stream)
        s.stream.seek(0)
        s._api_experiment.log_asset(IgnoreArgument(), overwrite=True, name="comet_llm_data.json")
        s._api_experiment.log_parameters(metadata)

        llm_trace.log_metadata(
            metadata=metadata,
            override=True,
        )


def test_log_metadata__override_false():
    json_stream = Fake("stream")
    metadata = {"metadata-key": "metadata-value"}
    trace_data = {
        "metadata": {
            "old--key": "old-value",
        },
    }
    expected_trace_data = {
        "metadata": {
            "metadata-key": "metadata-value",
            "old--key": "old-value",
        },
    }

    experiment = Fake("_api_experiment")
    llm_trace = llm_trace_api.LLMTraceAPI.__api__from_api_experiment__(experiment)

    with Scenario() as s:
        s._get_trace_data() >> trace_data
        s.io.StringIO() >> json_stream
        s.json.dump(expected_trace_data, json_stream)
        s.stream.seek(0)
        s._api_experiment.log_asset(IgnoreArgument(), overwrite=True, name="comet_llm_data.json")
        s._api_experiment.log_parameters(metadata)

        llm_trace.log_metadata(
            metadata=metadata,
            override=False,
        )
