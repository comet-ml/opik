"""Converters between raw dataset/REST formats and test suite formats.

The two key adapter functions provide bidirectional conversion:

* :func:`dataset_item_to_suite_item_dict` — DatasetItem → TestSuiteItem (exports)
* :func:`suite_item_dict_to_dataset_item` — TestSuiteItem → DatasetItem (imports)

These adapters bridge a **structural** gap between the flat DatasetItem model
(extra fields stored via pydantic ``model_extra``) and the nested TestSuiteItem
format (``data``, ``assertions``, ``execution_policy``).  Because of this
structural difference the generic ``dataset/converters`` serialisation helpers
cannot be reused directly for test-suite I/O.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional, TYPE_CHECKING

if TYPE_CHECKING:
    import pandas as pd

    from opik.evaluation.suite_evaluators.llm_judge import LLMJudge

from opik import id_helpers
from opik.api_objects.dataset import dataset_item, validators, helpers
from opik.api_objects.dataset.test_suite import types as suite_types
from opik.rest_api.types import (
    evaluator_item_public as rest_evaluator_item_public,
    execution_policy_public as rest_execution_policy_public,
)
from .. import execution_policy


def evaluators_to_assertions(evaluators: List[LLMJudge]) -> List[str]:
    """Extract assertion strings from a list of LLMJudge instances."""
    assertions: List[str] = []
    for evaluator in evaluators:
        assertions.extend(evaluator.assertions)
    return assertions


def version_evaluators_to_assertions(
    evaluators: Optional[List[rest_evaluator_item_public.EvaluatorItemPublic]],
) -> List[str]:
    """Extract assertion strings from REST evaluator items on a dataset version."""
    from opik.evaluation.suite_evaluators import llm_judge
    from opik.evaluation.suite_evaluators.llm_judge import config as llm_judge_config

    assertions: List[str] = []
    if evaluators:
        for evaluator in evaluators:
            if evaluator.type == "llm_judge":
                cfg = llm_judge_config.LLMJudgeConfig(**evaluator.config)
                judge = llm_judge.LLMJudge.from_config(cfg)
                assertions.extend(judge.assertions)
    return assertions


def version_policy_to_execution_policy(
    policy: Optional[rest_execution_policy_public.ExecutionPolicyPublic],
) -> execution_policy.ExecutionPolicy:
    """Convert a REST execution policy object to an ExecutionPolicy dict."""
    if policy:
        return execution_policy.ExecutionPolicy(
            runs_per_item=policy.runs_per_item or 1,
            pass_threshold=policy.pass_threshold or 1,
        )
    return execution_policy.DEFAULT_EXECUTION_POLICY.copy()


def dataset_item_to_suite_item_dict(
    item: dataset_item.DatasetItem,
) -> suite_types.TestSuiteItem:
    """Convert a DatasetItem into a TestSuiteItem dict with decoded assertions."""
    result = suite_types.TestSuiteItem(
        id=item.id,
        data=item.get_content(),
        assertions=version_evaluators_to_assertions(item.evaluators),
    )
    if item.description is not None:
        result["description"] = item.description
    if item.execution_policy is not None:
        result["execution_policy"] = {
            "runs_per_item": item.execution_policy.runs_per_item or 1,
            "pass_threshold": item.execution_policy.pass_threshold or 1,
        }
    return result


def suite_item_dict_to_dataset_item(
    item: suite_types.TestSuiteItem,
) -> dataset_item.DatasetItem:
    """Convert a TestSuiteItem dict into a DatasetItem with evaluators.

    This is the inverse of :func:`dataset_item_to_suite_item_dict`.
    """
    evaluators = validators.resolve_evaluators(
        item.get("assertions"), None, "item-level assertions"
    )

    evaluator_items = None
    if evaluators:
        evaluator_items = [
            dataset_item.EvaluatorItem(
                name=e.name,
                type="llm_judge",
                config=e.to_config().model_dump(by_alias=True),
            )
            for e in evaluators
        ]

    ep = item.get("execution_policy")
    execution_policy_item = None
    if ep:
        execution_policy_item = dataset_item.ExecutionPolicyItem(
            runs_per_item=ep.get("runs_per_item"),
            pass_threshold=ep.get("pass_threshold"),
        )

    return dataset_item.DatasetItem(
        id=item.get("id", id_helpers.generate_id()),
        description=item.get("description"),
        evaluators=evaluator_items,
        execution_policy=execution_policy_item,
        **item["data"],
    )


# ---------------------------------------------------------------------------
# Export
# ---------------------------------------------------------------------------


def to_json(items: List[suite_types.TestSuiteItem]) -> str:
    """Serialise a list of TestSuiteItem dicts to a JSON string."""
    return json.dumps(items, indent=2)


def to_pandas(items: List[suite_types.TestSuiteItem]) -> "pd.DataFrame":
    """Convert a list of TestSuiteItem dicts to a pandas DataFrame."""
    helpers.raise_if_pandas_is_unavailable()

    import pandas as pd

    return pd.DataFrame(items)


# ---------------------------------------------------------------------------
# Import
# ---------------------------------------------------------------------------


def _apply_key_mapping(
    item_dict: Dict[str, Any],
    keys_mapping: Dict[str, str],
    ignore_keys: List[str],
) -> Dict[str, Any]:
    return {
        keys_mapping.get(key, key): value
        for key, value in item_dict.items()
        if key not in ignore_keys
    }


def _from_dicts(
    item_dicts: List[Dict[str, Any]],
    keys_mapping: Dict[str, str],
    ignore_keys: List[str],
) -> List[suite_types.TestSuiteItem]:
    return [
        _apply_key_mapping(d, keys_mapping, ignore_keys)  # type: ignore[misc]
        for d in item_dicts
    ]


def from_json(
    value: str,
    keys_mapping: Dict[str, str],
    ignore_keys: List[str],
) -> List[suite_types.TestSuiteItem]:
    """Parse a JSON array string into a list of TestSuiteItem dicts."""
    parsed = json.loads(value)
    if not isinstance(parsed, list):
        raise ValueError(
            f"JSON input must be an array of objects, got {type(parsed).__name__}."
        )
    return _from_dicts(parsed, keys_mapping, ignore_keys)


def from_pandas(
    dataframe: "pd.DataFrame",
    keys_mapping: Dict[str, str],
    ignore_keys: List[str],
) -> List[suite_types.TestSuiteItem]:
    """Convert pandas DataFrame rows into a list of TestSuiteItem dicts."""
    helpers.raise_if_pandas_is_unavailable()

    items: List[suite_types.TestSuiteItem] = []
    for record in dataframe.to_dict(orient="records"):
        mapped: Dict[str, Any] = {}
        for key, value in record.items():
            if key in ignore_keys:
                continue
            # pandas stores missing optional fields as float NaN
            if isinstance(value, float) and value != value:
                continue
            mapped[keys_mapping.get(key, key)] = value
        items.append(mapped)  # type: ignore[arg-type]
    return items


def from_jsonl_file(
    file_path: str,
    keys_mapping: Dict[str, str],
    ignore_keys: List[str],
) -> List[suite_types.TestSuiteItem]:
    """Read a JSONL file into a list of TestSuiteItem dicts."""
    raw_items: List[Dict[str, Any]] = []
    with open(file_path, "r", encoding="utf-8") as file:
        for line in file:
            line = line.strip()
            if line:
                raw_items.append(json.loads(line))

    return _from_dicts(raw_items, keys_mapping, ignore_keys)
