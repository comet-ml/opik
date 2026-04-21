"""Unit tests for test_suite.converters module."""

import json
import os
import tempfile

import pandas as pd
import pandas.testing
import pytest
from unittest import mock

from opik.api_objects.dataset import dataset_item
from opik.api_objects.dataset.test_suite import converters


# ---------------------------------------------------------------------------
# evaluators_to_assertions
# ---------------------------------------------------------------------------


def test_evaluators_to_assertions__multiple_evaluators__concatenates():
    e1 = mock.MagicMock()
    e1.assertions = ["A1"]
    e2 = mock.MagicMock()
    e2.assertions = ["A2", "A3"]

    assert converters.evaluators_to_assertions([e1, e2]) == ["A1", "A2", "A3"]


def test_evaluators_to_assertions__empty_list__returns_empty():
    assert converters.evaluators_to_assertions([]) == []


# ---------------------------------------------------------------------------
# version_evaluators_to_assertions
# ---------------------------------------------------------------------------


def test_version_evaluators_to_assertions__llm_judge__extracts_assertions():
    from opik.evaluation.suite_evaluators import LLMJudge

    judge = LLMJudge(assertions=["Response is accurate"], track=False)
    config = judge.to_config().model_dump(by_alias=True)

    evaluator_item = mock.MagicMock()
    evaluator_item.type = "llm_judge"
    evaluator_item.config = config

    assert converters.version_evaluators_to_assertions([evaluator_item]) == [
        "Response is accurate"
    ]


def test_version_evaluators_to_assertions__none__returns_empty():
    assert converters.version_evaluators_to_assertions(None) == []


def test_version_evaluators_to_assertions__non_llm_judge__skipped():
    evaluator_item = mock.MagicMock()
    evaluator_item.type = "custom_scorer"

    assert converters.version_evaluators_to_assertions([evaluator_item]) == []


# ---------------------------------------------------------------------------
# version_policy_to_execution_policy
# ---------------------------------------------------------------------------


def test_version_policy_to_execution_policy__converts():
    policy = mock.MagicMock()
    policy.runs_per_item = 5
    policy.pass_threshold = 3

    assert converters.version_policy_to_execution_policy(policy) == {
        "runs_per_item": 5,
        "pass_threshold": 3,
    }


def test_version_policy_to_execution_policy__none__returns_default():
    assert converters.version_policy_to_execution_policy(None) == {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }


# ---------------------------------------------------------------------------
# dataset_item_to_suite_item_dict (DatasetItem → TestSuiteItem)
# ---------------------------------------------------------------------------


def test_dataset_item_to_suite_item_dict__all_fields():
    item = dataset_item.DatasetItem(
        id="item-1",
        description="Test item",
        question="What is 2+2?",
        execution_policy=dataset_item.ExecutionPolicyItem(
            runs_per_item=3, pass_threshold=2
        ),
    )

    assert converters.dataset_item_to_suite_item_dict(item) == {
        "id": "item-1",
        "data": {"question": "What is 2+2?"},
        "assertions": [],
        "description": "Test item",
        "execution_policy": {"runs_per_item": 3, "pass_threshold": 2},
    }


def test_dataset_item_to_suite_item_dict__minimal():
    item = dataset_item.DatasetItem(id="item-1", question="Hello")

    result = converters.dataset_item_to_suite_item_dict(item)

    assert result == {"id": "item-1", "data": {"question": "Hello"}, "assertions": []}
    assert "description" not in result
    assert "execution_policy" not in result


def test_dataset_item_to_suite_item_dict__with_evaluators__extracts_assertions():
    from opik.evaluation.suite_evaluators import LLMJudge

    judge = LLMJudge(assertions=["Is correct"], track=False)
    config = judge.to_config().model_dump(by_alias=True)

    item = dataset_item.DatasetItem(
        id="item-2",
        evaluators=[
            dataset_item.EvaluatorItem(
                name="llm_judge", type="llm_judge", config=config
            ),
        ],
        question="Hello",
    )

    assert converters.dataset_item_to_suite_item_dict(item)["assertions"] == [
        "Is correct"
    ]


# ---------------------------------------------------------------------------
# suite_item_dict_to_dataset_item (TestSuiteItem → DatasetItem)
# ---------------------------------------------------------------------------


def test_suite_item_dict_to_dataset_item__all_fields():
    item = {
        "id": "item-1",
        "data": {"question": "Hello", "context": "test"},
        "assertions": ["Is polite"],
        "description": "A test case",
        "execution_policy": {"runs_per_item": 5, "pass_threshold": 3},
    }

    ds_item = converters.suite_item_dict_to_dataset_item(item)

    assert ds_item.id == "item-1"
    assert ds_item.get_content() == {"question": "Hello", "context": "test"}
    assert ds_item.description == "A test case"
    assert ds_item.execution_policy is not None
    assert ds_item.execution_policy.runs_per_item == 5
    assert ds_item.execution_policy.pass_threshold == 3
    assert ds_item.evaluators is not None
    assert len(ds_item.evaluators) == 1
    assert ds_item.evaluators[0].type == "llm_judge"


def test_suite_item_dict_to_dataset_item__minimal__generates_id():
    ds_item = converters.suite_item_dict_to_dataset_item(
        {"data": {"question": "Hello"}}
    )

    assert ds_item.id is not None
    assert len(ds_item.id) > 0
    assert ds_item.get_content() == {"question": "Hello"}
    assert ds_item.evaluators is None
    assert ds_item.execution_policy is None


# ---------------------------------------------------------------------------
# to_json / from_json
# ---------------------------------------------------------------------------


SAMPLE_ITEMS = [
    {
        "id": "item-1",
        "data": {"question": "How do I get a refund?", "context": "Premium user"},
        "assertions": ["Response is polite"],
        "description": "Refund scenario",
        "execution_policy": {"runs_per_item": 3, "pass_threshold": 2},
    },
    {
        "id": "item-2",
        "data": {"question": "Is my account hacked?"},
        "assertions": [],
    },
]


def test_to_json__happyflow():
    EXPECTED = [
        {
            "id": "item-1",
            "data": {"question": "How do I get a refund?", "context": "Premium user"},
            "assertions": ["Response is polite"],
            "description": "Refund scenario",
            "execution_policy": {"runs_per_item": 3, "pass_threshold": 2},
        },
        {
            "id": "item-2",
            "data": {"question": "Is my account hacked?"},
            "assertions": [],
        },
    ]

    assert json.loads(converters.to_json(SAMPLE_ITEMS)) == EXPECTED


def test_to_json__empty_list():
    assert json.loads(converters.to_json([])) == []


def test_from_json__happyflow():
    json_str = json.dumps(
        [
            {"data": {"question": "Hello"}, "assertions": ["Is polite"]},
            {"data": {"question": "Bye"}},
        ]
    )

    EXPECTED = [
        {"data": {"question": "Hello"}, "assertions": ["Is polite"]},
        {"data": {"question": "Bye"}},
    ]

    assert converters.from_json(json_str, {}, []) == EXPECTED


def test_from_json__with_keys_mapping():
    json_str = json.dumps(
        [
            {"test_data": {"question": "Hello"}, "checks": ["Is polite"]},
        ]
    )

    EXPECTED = [{"data": {"question": "Hello"}, "assertions": ["Is polite"]}]

    assert (
        converters.from_json(
            json_str, {"test_data": "data", "checks": "assertions"}, []
        )
        == EXPECTED
    )


def test_from_json__with_ignore_keys():
    json_str = json.dumps(
        [
            {"data": {"question": "Hello"}, "internal_note": "skip this"},
        ]
    )

    EXPECTED = [{"data": {"question": "Hello"}}]

    assert converters.from_json(json_str, {}, ["internal_note"]) == EXPECTED


def test_from_json__non_array__raises_value_error():
    json_str = json.dumps({"data": {"question": "Hello"}})

    with pytest.raises(ValueError, match="must be an array"):
        converters.from_json(json_str, {}, [])


# ---------------------------------------------------------------------------
# to_pandas / from_pandas
# ---------------------------------------------------------------------------


def test_to_pandas__happyflow():
    EXPECTED = pd.DataFrame(
        [
            {
                "id": "item-1",
                "data": {
                    "question": "How do I get a refund?",
                    "context": "Premium user",
                },
                "assertions": ["Response is polite"],
                "description": "Refund scenario",
                "execution_policy": {"runs_per_item": 3, "pass_threshold": 2},
            },
            {
                "id": "item-2",
                "data": {"question": "Is my account hacked?"},
                "assertions": [],
            },
        ]
    )

    pandas.testing.assert_frame_equal(converters.to_pandas(SAMPLE_ITEMS), EXPECTED)


def test_to_pandas__empty_list():
    assert len(converters.to_pandas([])) == 0


def test_from_pandas__happyflow():
    dataframe = pd.DataFrame(
        [
            {"data": {"question": "Hello"}, "assertions": ["Is polite"]},
            {"data": {"question": "Bye"}},
        ]
    )

    EXPECTED = [
        {"data": {"question": "Hello"}, "assertions": ["Is polite"]},
        {"data": {"question": "Bye"}},
    ]

    assert converters.from_pandas(dataframe, {}, []) == EXPECTED


def test_from_pandas__with_keys_mapping():
    dataframe = pd.DataFrame([{"test_data": {"question": "Hello"}}])

    EXPECTED = [{"data": {"question": "Hello"}}]

    assert converters.from_pandas(dataframe, {"test_data": "data"}, []) == EXPECTED


def test_from_pandas__nan_values__skipped():
    dataframe = pd.DataFrame(
        [
            {"data": {"question": "Hello"}, "assertions": ["Is polite"]},
            {"data": {"question": "Bye"}, "assertions": float("nan")},
        ]
    )

    result = converters.from_pandas(dataframe, {}, [])

    assert result[0] == {"data": {"question": "Hello"}, "assertions": ["Is polite"]}
    assert result[1] == {"data": {"question": "Bye"}}


# ---------------------------------------------------------------------------
# from_jsonl_file
# ---------------------------------------------------------------------------


def test_from_jsonl_file__happyflow():
    jsonl_content = (
        '{"data": {"question": "What is 2+2?"}, "assertions": ["Is correct"]}\n'
        '{"data": {"question": "Capital of France?"}}\n'
    )
    with tempfile.NamedTemporaryFile(mode="w", delete=False) as f:
        f.write(jsonl_content)
        path = f.name

    try:
        EXPECTED = [
            {"data": {"question": "What is 2+2?"}, "assertions": ["Is correct"]},
            {"data": {"question": "Capital of France?"}},
        ]

        assert converters.from_jsonl_file(path, {}, []) == EXPECTED
    finally:
        os.unlink(path)


def test_from_jsonl_file__empty_lines__skipped():
    jsonl_content = '{"data": {"question": "Q1"}}\n\n{"data": {"question": "Q2"}}\n\n'
    with tempfile.NamedTemporaryFile(mode="w", delete=False) as f:
        f.write(jsonl_content)
        path = f.name

    try:
        assert len(converters.from_jsonl_file(path, {}, [])) == 2
    finally:
        os.unlink(path)


def test_from_jsonl_file__with_keys_mapping():
    jsonl_content = '{"test_data": {"question": "Hello"}}\n'
    with tempfile.NamedTemporaryFile(mode="w", delete=False) as f:
        f.write(jsonl_content)
        path = f.name

    try:
        EXPECTED = [{"data": {"question": "Hello"}}]

        assert converters.from_jsonl_file(path, {"test_data": "data"}, []) == EXPECTED
    finally:
        os.unlink(path)


# ---------------------------------------------------------------------------
# Round-trip tests
# ---------------------------------------------------------------------------


def test_adapter_roundtrip__suite_to_dataset_to_suite__preserves_all_fields():
    original = {
        "id": "item-1",
        "data": {"question": "Hello", "context": "Premium"},
        "assertions": ["Is polite", "Is helpful"],
        "description": "Test case",
        "execution_policy": {"runs_per_item": 3, "pass_threshold": 2},
    }

    ds_item = converters.suite_item_dict_to_dataset_item(original)
    recovered = converters.dataset_item_to_suite_item_dict(ds_item)

    assert recovered["id"] == original["id"]
    assert recovered["data"] == original["data"]
    assert sorted(recovered["assertions"]) == sorted(original["assertions"])
    assert recovered["description"] == original["description"]
    assert recovered["execution_policy"] == original["execution_policy"]


def test_adapter_roundtrip__two_cycles__stable():
    original = {
        "id": "item-1",
        "data": {"question": "Hello"},
        "assertions": ["Is polite"],
        "execution_policy": {"runs_per_item": 5, "pass_threshold": 3},
    }

    suite_item = original
    for _ in range(2):
        ds_item = converters.suite_item_dict_to_dataset_item(suite_item)
        suite_item = converters.dataset_item_to_suite_item_dict(ds_item)

    assert suite_item["data"] == original["data"]
    assert suite_item["assertions"] == original["assertions"]
    assert suite_item["execution_policy"] == original["execution_policy"]


def test_json_roundtrip__export_import_export__stable():
    json_str_1 = converters.to_json(SAMPLE_ITEMS)
    imported = converters.from_json(json_str_1, {}, [])
    json_str_2 = converters.to_json(imported)

    assert json.loads(json_str_1) == json.loads(json_str_2)


def test_json_roundtrip__import_export_import__stable():
    json_str = json.dumps(
        [
            {
                "data": {"question": "Hello"},
                "assertions": ["Is polite"],
                "description": "Test",
                "execution_policy": {"runs_per_item": 3, "pass_threshold": 2},
            },
        ]
    )

    items_1 = converters.from_json(json_str, {}, [])
    exported = converters.to_json(items_1)
    items_2 = converters.from_json(exported, {}, [])

    assert items_1 == items_2


def test_pandas_roundtrip__export_import_export__stable():
    df_1 = converters.to_pandas(SAMPLE_ITEMS)
    imported = converters.from_pandas(df_1, {}, [])
    df_2 = converters.to_pandas(imported)

    pandas.testing.assert_frame_equal(df_1, df_2)


def test_full_roundtrip__json_through_adapters__two_cycles():
    """JSON → from_json → adapter → adapter → to_json, repeated twice."""
    original_json = json.dumps(
        [
            {
                "id": "item-1",
                "data": {"question": "Refund?", "tier": "premium"},
                "assertions": ["Is polite", "No hallucination"],
                "description": "Refund scenario",
                "execution_policy": {"runs_per_item": 3, "pass_threshold": 2},
            },
        ]
    )

    current_json = original_json
    for _ in range(2):
        suite_items = converters.from_json(current_json, {}, [])
        ds_items = [converters.suite_item_dict_to_dataset_item(i) for i in suite_items]
        suite_items = [converters.dataset_item_to_suite_item_dict(i) for i in ds_items]
        current_json = converters.to_json(suite_items)

    EXPECTED = {
        "id": "item-1",
        "data": {"question": "Refund?", "tier": "premium"},
        "assertions": ["Is polite", "No hallucination"],
        "description": "Refund scenario",
        "execution_policy": {"runs_per_item": 3, "pass_threshold": 2},
    }

    final = json.loads(current_json)
    assert len(final) == 1
    result = final[0]
    assert result["id"] == EXPECTED["id"]
    assert result["data"] == EXPECTED["data"]
    assert sorted(result["assertions"]) == sorted(EXPECTED["assertions"])
    assert result["description"] == EXPECTED["description"]
    assert result["execution_policy"] == EXPECTED["execution_policy"]
