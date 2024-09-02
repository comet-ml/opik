import pandas as pd
import pandas.testing
import json

from opik.api_objects.dataset import converters
from opik import DatasetItem


def test_from_pandas__all_columns_from_dataframe_represent_all_dataset_item_fields():
    data_for_dataframe = {
        "id": ["id-1", "id-2"],
        "input": [{"input-key-1": "input-1"}, {"input-key-2": "input-2"}],
        "expected_output": [
            {"expected-output-key-1": "expected-output-1"},
            {"expected-output-key-2": "expected-output-2"},
        ],
        "metadata": [{"metadata-key-1": "v1"}, {"metadata-key-2": "v2"}],
        "span_id": ["span-id-1", "span-id-2"],
        "trace_id": ["trace-id-1", "trace-id-2"],
        "source": ["some-source-1", "some-source-2"],
    }

    EXPECTED_ITEMS = [
        DatasetItem(
            id="id-1",
            input={"input-key-1": "input-1"},
            expected_output={"expected-output-key-1": "expected-output-1"},
            metadata={"metadata-key-1": "v1"},
            span_id="span-id-1",
            trace_id="trace-id-1",
            source="some-source-1",
        ),
        DatasetItem(
            id="id-2",
            input={"input-key-2": "input-2"},
            expected_output={"expected-output-key-2": "expected-output-2"},
            metadata={"metadata-key-2": "v2"},
            span_id="span-id-2",
            trace_id="trace-id-2",
            source="some-source-2",
        ),
    ]

    dataframe = pd.DataFrame(data_for_dataframe)

    actual_items = converters.from_pandas(
        dataframe=dataframe, keys_mapping={}, ignore_keys=[]
    )

    assert actual_items == EXPECTED_ITEMS


def test_from_pandas__only_input_presented_in_dataframe__items_are_constructed_with_default_values_for_missing_fields():
    data_for_dataframe = {
        "input": [{"input-key-1": "input-1"}, {"input-key-2": "input-2"}],
    }

    EXPECTED_ITEMS = [
        DatasetItem(
            input={"input-key-1": "input-1"},
            source="sdk",
        ),
        DatasetItem(
            input={"input-key-2": "input-2"},
            source="sdk",
        ),
    ]

    dataframe = pd.DataFrame(data_for_dataframe)

    actual_items = converters.from_pandas(
        dataframe=dataframe, keys_mapping={}, ignore_keys=[]
    )

    assert actual_items == EXPECTED_ITEMS


def test_from_pandas__dataframe_column_does_not_have_the_same_name_as_dataset_item_field__keys_mapping_is_used():
    data_for_dataframe = {
        "Input column name": [{"input-key-1": "input-1"}, {"input-key-2": "input-2"}],
    }

    EXPECTED_ITEMS = [
        DatasetItem(
            input={"input-key-1": "input-1"},
            source="sdk",
        ),
        DatasetItem(
            input={"input-key-2": "input-2"},
            source="sdk",
        ),
    ]

    dataframe = pd.DataFrame(data_for_dataframe)

    actual_items = converters.from_pandas(
        dataframe=dataframe, keys_mapping={"Input column name": "input"}, ignore_keys=[]
    )

    assert actual_items == EXPECTED_ITEMS


def test_from_pandas__dataframe_contains_extra_column_not_needed_for_dataset_item__ignore_keys_is_used():
    data_for_dataframe = {
        "input": [{"input-key-1": "input-1"}, {"input-key-2": "input-2"}],
        "some-extra-column": [1, 2],
    }

    EXPECTED_ITEMS = [
        DatasetItem(
            input={"input-key-1": "input-1"},
            source="sdk",
        ),
        DatasetItem(
            input={"input-key-2": "input-2"},
            source="sdk",
        ),
    ]

    dataframe = pd.DataFrame(data_for_dataframe)

    actual_items = converters.from_pandas(
        dataframe=dataframe, keys_mapping={}, ignore_keys=["some-extra-column"]
    )

    assert actual_items == EXPECTED_ITEMS


def test_to_pandas__with_keys_mapping__happyflow():
    EXPECTED_DATAFRAME = pd.DataFrame(
        {
            "id": ["id-1", "id-2"],
            "input": [{"input-key-1": "input-1"}, {"input-key-2": "input-2"}],
            "Customized expected output": [
                {"expected-output-key-1": "expected-output-1"},
                {"expected-output-key-2": "expected-output-2"},
            ],
            "metadata": [{"metadata-key-1": "v1"}, {"metadata-key-2": "v2"}],
            "span_id": ["span-id-1", "span-id-2"],
            "trace_id": ["trace-id-1", "trace-id-2"],
            "source": ["some-source-1", "some-source-2"],
        }
    )

    input_items = [
        DatasetItem(
            id="id-1",
            input={"input-key-1": "input-1"},
            expected_output={"expected-output-key-1": "expected-output-1"},
            metadata={"metadata-key-1": "v1"},
            span_id="span-id-1",
            trace_id="trace-id-1",
            source="some-source-1",
        ),
        DatasetItem(
            id="id-2",
            input={"input-key-2": "input-2"},
            expected_output={"expected-output-key-2": "expected-output-2"},
            metadata={"metadata-key-2": "v2"},
            span_id="span-id-2",
            trace_id="trace-id-2",
            source="some-source-2",
        ),
    ]

    actual_dataframe = converters.to_pandas(
        input_items, keys_mapping={"expected_output": "Customized expected output"}
    )

    # check_like ignores columns and rows order
    pandas.testing.assert_frame_equal(
        actual_dataframe, EXPECTED_DATAFRAME, check_like=True
    )


def test_from_json__all_columns_from_dataframe_represent_all_dataset_item_fields():
    input_json = """
    [
        {
            "id": "id-1",
            "input": {"input-key-1": "input-1"},
            "expected_output": {"expected-output-key-1": "expected-output-1"},
            "metadata": {"metadata-key-1": "v1"},
            "span_id": "span-id-1",
            "trace_id": "trace-id-1",
            "source": "some-source-1"
        },
        {
            "id": "id-2",
            "input": {"input-key-2": "input-2"},
            "expected_output": {"expected-output-key-2": "expected-output-2"},
            "metadata": {"metadata-key-2": "v2"},
            "span_id": "span-id-2",
            "trace_id": "trace-id-2",
            "source": "some-source-2"
        }
    ]
"""
    EXPECTED_ITEMS = [
        DatasetItem(
            id="id-1",
            input={"input-key-1": "input-1"},
            expected_output={"expected-output-key-1": "expected-output-1"},
            metadata={"metadata-key-1": "v1"},
            span_id="span-id-1",
            trace_id="trace-id-1",
            source="some-source-1",
        ),
        DatasetItem(
            id="id-2",
            input={"input-key-2": "input-2"},
            expected_output={"expected-output-key-2": "expected-output-2"},
            metadata={"metadata-key-2": "v2"},
            span_id="span-id-2",
            trace_id="trace-id-2",
            source="some-source-2",
        ),
    ]

    actual_items = converters.from_json(input_json, keys_mapping={}, ignore_keys=[])

    assert actual_items == EXPECTED_ITEMS


def test_from_json__only_input_presented_in_json__items_are_constructed_with_default_values_for_missing_fields():
    input_json = """
    [
        {
            "input": {"input-key-1": "input-1"}
        },
        {
            "input": {"input-key-2": "input-2"}
        }
    ]
    """

    EXPECTED_ITEMS = [
        DatasetItem(
            input={"input-key-1": "input-1"},
            source="sdk",
        ),
        DatasetItem(
            input={"input-key-2": "input-2"},
            source="sdk",
        ),
    ]

    actual_items = converters.from_json(input_json, keys_mapping={}, ignore_keys=[])

    assert actual_items == EXPECTED_ITEMS


def test_from_json__json_objects_contain_extra_key_not_needed_for_dataset_item__ignore_keys_is_used():
    input_json = """
    [
        {
            "input": {"input-key-1": "input-1"},
            "extra_key": 42
        },
        {
            "input": {"input-key-2": "input-2"},
            "extra_key": 4242
        }
    ]
    """

    EXPECTED_ITEMS = [
        DatasetItem(
            input={"input-key-1": "input-1"},
            source="sdk",
        ),
        DatasetItem(
            input={"input-key-2": "input-2"},
            source="sdk",
        ),
    ]

    actual_items = converters.from_json(
        input_json, keys_mapping={}, ignore_keys=["extra_key"]
    )

    assert actual_items == EXPECTED_ITEMS


def test_from_json__json_objects_dont_have_the_same_name_as_dataset_item_field__keys_mapping_is_used():
    input_json = """
    [
        {
            "JSON input key": {"input-key-1": "input-1"}
        },
        {
            "JSON input key": {"input-key-2": "input-2"}
        }
    ]
    """
    EXPECTED_ITEMS = [
        DatasetItem(
            input={"input-key-1": "input-1"},
            source="sdk",
        ),
        DatasetItem(
            input={"input-key-2": "input-2"},
            source="sdk",
        ),
    ]

    actual_items = converters.from_json(
        input_json, keys_mapping={"JSON input key": "input"}, ignore_keys=[]
    )

    assert actual_items == EXPECTED_ITEMS


def test_to_json__with_keys_mapping__happyflow():
    EXPECTED_JSON = """
    [
        {
            "id": "id-1",
            "input": {"input-key-1": "input-1"},
            "Customized expected output": {"expected-output-key-1": "expected-output-1"},
            "metadata": {"metadata-key-1": "v1"},
            "span_id": "span-id-1",
            "trace_id": "trace-id-1",
            "source": "some-source-1"
        },
        {
            "id": "id-2",
            "input": {"input-key-2": "input-2"},
            "Customized expected output": {"expected-output-key-2": "expected-output-2"},
            "metadata": {"metadata-key-2": "v2"},
            "span_id": "span-id-2",
            "trace_id": "trace-id-2",
            "source": "some-source-2"
        }
    ]
    """
    input_items = [
        DatasetItem(
            id="id-1",
            input={"input-key-1": "input-1"},
            expected_output={"expected-output-key-1": "expected-output-1"},
            metadata={"metadata-key-1": "v1"},
            span_id="span-id-1",
            trace_id="trace-id-1",
            source="some-source-1",
        ),
        DatasetItem(
            id="id-2",
            input={"input-key-2": "input-2"},
            expected_output={"expected-output-key-2": "expected-output-2"},
            metadata={"metadata-key-2": "v2"},
            span_id="span-id-2",
            trace_id="trace-id-2",
            source="some-source-2",
        ),
    ]

    actual_json = converters.to_json(
        input_items, keys_mapping={"expected_output": "Customized expected output"}
    )

    assert json.loads(actual_json) == json.loads(EXPECTED_JSON)
