from typing import Any, Dict, List, Optional

import comet_ml

from .. import testlib


def verify_trace(
        comet_api: "comet_ml.API",
        trace_id: str,
        expected_duration: Optional[float] = None,
        expected_tags: Optional[List[str]] = None,
        expected_metadata: Optional[Dict[str, Any]] = None,
    ):
    """
    Performs assertions for various trace (prompt | chain) attributes.
    As of today it can check the fact that:
        - Trace was saved on the backend side (as experiment)
        - It contains comet_llm_data.json asset
        - Expected duration, tags, metadata are the same as the actual ones.

    The function takes into account that some data might not be available
    right after logging, so it can wait for some pieces of data (except for the check
    for trace and asset existence).

    TODO: probably add assertions for asset content. E.g. today trace input and output
    are not verified, however, they are
    """
    api_experiment: "comet_ml.APIExperiment" = comet_api.get_experiment_by_id(experiment=trace_id)
    assert api_experiment is not None, "Failed to verify that trace was saved"

    assets = api_experiment.get_asset_list()
    assert len(assets) == 1, "Failed to verify that trace contains asset"
    assert assets[0]["fileName"] == "comet_llm_data.json"

    if expected_duration is not None:
        testlib.until(
            function=lambda: len(api_experiment.get_metrics(metric="chain_duration")) != 0
        ), "Failed to get duration (a.k.a. chain_duration metric)"
        metrics = api_experiment.get_metrics(metric="chain_duration")
        _assert_equal_with_conversion_to_left_type(
            expected_duration,
            metrics[0]["metricValue"]
        )

    if expected_tags is not None:
        assert testlib.until(
            function=lambda: len(api_experiment.get_tags()) != 0
        ), "Failed to get tags"
        actual_tags = api_experiment.get_tags()
        assert actual_tags == expected_tags

    if expected_metadata is not None:
        assert testlib.until(
            function=lambda: len(api_experiment.get_parameters_summary()) != 0
        ), "Failed to get trace metadata (a.k.a. parameters)"
        actual_parameters = api_experiment.get_parameters_summary()
        assert len(actual_parameters) == len(expected_metadata)
        for actual_parameter in actual_parameters:
            name = actual_parameter["name"]
            _assert_equal_with_conversion_to_left_type(
                expected_metadata[name],
                actual_parameter["valueCurrent"]
            )


def _assert_equal_with_conversion_to_left_type(left_value: Any, right_value: Any) -> None:
    """
    Used for more convenient assertions with
    string data returned from backend
    """
    left_type = type(left_value)
    right_value_converted = left_type(right_value)

    assert left_value == right_value_converted
