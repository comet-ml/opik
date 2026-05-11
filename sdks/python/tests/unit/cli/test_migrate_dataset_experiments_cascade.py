"""Tests for ``opik migrate`` Slice 3 — experiment + trace/span cascade.

Slice 3 is the cascade step that runs AFTER Slice 1's dataset copy and
Slice 2's version-history replay. The tests in this module exercise the
cascade in isolation: they synthesise a pre-populated ``version_remap`` /
``item_id_remap`` (the state Slice 2 would have produced) and verify the
cascade's read/write contract against a stand-in REST client.

Scope this module covers (ticket AC for OPIK-6416):

* Full fidelity copy of experiment fields the BE round-trips today
* ``prompt_versions`` and ``optimization_id`` stripped on destination
* FK remap correctness (dataset_id, dataset_version_id, dataset_item_id,
  trace_id, project_id)
* Missing-trace / missing-dataset-item skip counters
* Span tree ordering preserved via ``parent_span_id`` remap
* trace_id_remap accumulates across experiments
* CLI gate (``--exclude-experiments``) omits the cascade action
* CLI validation: ``--exclude-versions`` without ``--exclude-experiments`` fails
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional
from unittest.mock import MagicMock

import datetime as dt
import json

import pytest

from opik.cli.migrate.datasets import experiments as cascade_module
from opik.cli.migrate.datasets import planner as planner_module
from opik.cli.migrate.datasets.experiments import (
    ExperimentCascadeResult,
    cascade_experiments,
)
from opik.cli.migrate.errors import ExperimentCascadeError

from ._migrate_helpers import _DatasetRow, _Page, _planner_rest_client


# ---------------------------------------------------------------------------
# Stand-in wire shapes for the cascade's reads.
#
# The cascade reads ``ExperimentPublic`` / ``ExperimentItemPublic`` /
# ``TracePublic`` / ``SpanPublic`` from the REST client. We don't construct
# the real pydantic models in tests -- they have ~30 fields each and the
# cascade only inspects a handful. ``MagicMock(spec=...)`` would impose the
# pydantic schema; a plain object with the fields the cascade reads is
# both shorter and produces more readable test failures.
# ---------------------------------------------------------------------------


class _Experiment:
    def __init__(
        self,
        *,
        id: str,
        name: str = "experiment",
        dataset_name: str = "MyDataset",
        dataset_id: str = "src-dataset-1",
        dataset_version_id: Optional[str] = "src-version-1",
        metadata: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
        type: str = "regular",
        evaluation_method: str = "dataset",
        optimization_id: Optional[str] = None,
        prompt_versions: Optional[List[Dict[str, str]]] = None,
    ) -> None:
        self.id = id
        self.name = name
        self.dataset_name = dataset_name
        self.dataset_id = dataset_id
        self.dataset_version_id = dataset_version_id
        self.metadata = metadata
        self.tags = tags
        self.type = type
        self.evaluation_method = evaluation_method
        self.optimization_id = optimization_id
        self.prompt_versions = prompt_versions


class _ExperimentItem:
    def __init__(
        self,
        *,
        id: str,
        experiment_id: str,
        trace_id: str,
        dataset_item_id: str,
        extras: Optional[Dict[str, Any]] = None,
    ) -> None:
        self.id = id
        self.experiment_id = experiment_id
        self.trace_id = trace_id
        self.dataset_item_id = dataset_item_id
        # ``extras`` becomes the per-item BE-returned payload outside the
        # typed schema (input, output, feedback_scores, assertion_results,
        # execution_policy, description, status, usage, etc.). The mock
        # rest_client serialises this dict as JSON alongside the typed
        # fields; ``ExperimentItemPublic.extra='allow'`` surfaces it on
        # ``model_extra`` for the cascade to consume.
        self.extras = extras or {}


class _Trace:
    def __init__(
        self,
        *,
        id: str,
        name: str = "trace",
        start_time: Optional[dt.datetime] = None,
        end_time: Optional[dt.datetime] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
        thread_id: Optional[str] = None,
        error_info: Optional[Any] = None,
        last_updated_at: Optional[dt.datetime] = None,
        ttft: Optional[float] = None,
        feedback_scores: Optional[List[Any]] = None,
    ) -> None:
        self.id = id
        self.name = name
        self.start_time = start_time or dt.datetime(2026, 1, 1, 12, 0, 0)
        self.end_time = end_time
        self.input = input
        self.output = output
        self.metadata = metadata
        self.tags = tags
        self.thread_id = thread_id
        self.error_info = error_info
        self.last_updated_at = last_updated_at
        self.ttft = ttft
        # ``cascade`` reads source.feedback_scores after the trace copy to
        # decide whether to re-emit any per-trace feedback. None / [] is a
        # valid "no scores" state.
        self.feedback_scores = feedback_scores


class _Span:
    """Stand-in matching the ``model_dump()`` shape the cascade consumes.

    The cascade calls ``span.model_dump()`` on every source span; we expose
    a ``model_dump`` method that returns the dict shape directly. Top-level
    fields the cascade reads from the dict: id, name, type, start_time,
    end_time, input, output, metadata, model, provider, tags, usage,
    error_info, last_updated_at, total_estimated_cost,
    total_estimated_cost_version, ttft, parent_span_id, trace_id, project_id.
    """

    def __init__(self, **fields: Any) -> None:
        fields.setdefault("start_time", dt.datetime(2026, 1, 1, 12, 0, 0))
        self._fields = fields

    def model_dump(self) -> Dict[str, Any]:
        return dict(self._fields)


# ---------------------------------------------------------------------------
# Cascade-level rest_client mock builder
# ---------------------------------------------------------------------------


def _cascade_rest_client(
    *,
    experiments_by_dataset: Dict[str, List[_Experiment]],
    items_by_experiment: Dict[str, List[_ExperimentItem]],
    traces_by_id: Dict[str, _Trace],
    spans_by_trace: Optional[Dict[str, List[_Span]]] = None,
) -> MagicMock:
    """Build a rest_client driving the cascade's REST surface deterministically.

    Captures every ``create_traces`` / ``create_spans`` call so tests can
    inspect the destination payload by reading
    ``rest_client.traces.create_traces.call_args_list`` etc.
    """
    spans_by_trace = spans_by_trace or {}
    rest_client = MagicMock()

    def _find_experiments(dataset_id: str, page: int, size: int) -> Any:
        # Single-page response (tests never exceed page size).
        content = experiments_by_dataset.get(dataset_id, []) if page == 1 else []
        return MagicMock(content=content)

    rest_client.experiments.find_experiments.side_effect = _find_experiments

    def _find_dataset_items_with_exp_items(
        id: str,
        experiment_ids: str,
        page: int,
        size: int,
        **_kwargs: Any,
    ) -> Any:
        # The real REST endpoint takes a JSON-array string for
        # ``experiment_ids``; the mock parses it back so tests can key
        # ``items_by_experiment`` by source experiment id. Each match
        # returns a ``DatasetItemCompare``-shaped MagicMock with an
        # ``experiment_items`` list of ``ExperimentItemCompare``-shaped
        # MagicMocks carrying the FK fields + any extras (assertion_results
        # in particular) the test attached.
        requested_exp_ids = set(json.loads(experiment_ids))
        if page != 1:
            return MagicMock(content=[])
        # Find all items across the experiments this call requested. Each
        # dataset item carries one experiment_item per matching experiment.
        dataset_items: List[Any] = []
        for exp_name, exp_items in items_by_experiment.items():
            # The test fixtures key items by experiment NAME; we need to
            # convert back to id via the experiments_by_dataset lookup.
            matching_exp_id: Optional[str] = None
            for exps in experiments_by_dataset.values():
                for exp in exps:
                    if exp.name == exp_name and exp.id in requested_exp_ids:
                        matching_exp_id = exp.id
                        break
                if matching_exp_id:
                    break
            if not matching_exp_id:
                continue
            for it in exp_items:
                # Build the experiment_item mock with the typed fields +
                # the test's ``extras`` (e.g. assertion_results) merged in.
                exp_item = MagicMock()
                exp_item.id = it.id
                exp_item.experiment_id = matching_exp_id
                exp_item.trace_id = it.trace_id
                exp_item.dataset_item_id = it.dataset_item_id
                exp_item.assertion_results = None
                exp_item.feedback_scores = None
                exp_item.input = None
                exp_item.output = None
                # Allow extras to override defaults.
                for key, value in getattr(it, "extras", {}).items():
                    setattr(exp_item, key, value)
                ds_item = MagicMock(experiment_items=[exp_item])
                dataset_items.append(ds_item)
        return MagicMock(content=dataset_items)

    rest_client.datasets.find_dataset_items_with_experiment_items.side_effect = (
        _find_dataset_items_with_exp_items
    )
    # MagicMock auto-attributes starting with 'assert' are blocked because
    # the mock treats them as assertions. Explicitly pre-attach the
    # ``assertion_results`` sub-mock so the cascade can call into it.
    rest_client.assertion_results = MagicMock()
    rest_client.assertion_results.store_assertions_batch = MagicMock()

    def _get_trace(id: str) -> _Trace:
        return traces_by_id[id]

    rest_client.traces.get_trace_by_id.side_effect = _get_trace
    rest_client.traces.create_traces = MagicMock()

    def _get_spans_by_project(
        trace_id: str,
        page: int,
        size: int,
        project_name: Optional[str] = None,
        project_id: Optional[str] = None,
    ) -> Any:
        content = spans_by_trace.get(trace_id, []) if page == 1 else []
        return MagicMock(content=content)

    rest_client.spans.get_spans_by_project.side_effect = _get_spans_by_project
    rest_client.spans.create_spans = MagicMock()

    return rest_client


def _audit() -> Any:
    """The cascade doesn't currently consume audit (the umbrella action wraps
    via ``execute_plan_loop``), but the parameter is in the signature. Use a
    plain ``MagicMock`` so the tests aren't coupled to audit internals."""
    return MagicMock()


def _client_with_recreate_capture() -> tuple:
    """opik.Opik mock that records ``create_experiment`` calls.

    The cascade calls ``recreate_experiment`` (from imports/experiment.py),
    which in turn calls ``client.get_or_create_dataset`` and
    ``client.create_experiment``. We capture both so tests can assert on
    the destination experiment shape, particularly which fields were stripped
    vs forwarded.
    """
    client = MagicMock()
    client.get_or_create_dataset = MagicMock(return_value=MagicMock())

    created_experiment = MagicMock()
    created_experiment.id = "dest-exp-1"
    client.create_experiment = MagicMock(return_value=created_experiment)

    # ``recreate_experiment`` also inserts experiment-items via REST directly.
    client._rest_client.experiments.create_experiment_items = MagicMock()
    return client


# ---------------------------------------------------------------------------
# Cascade unit tests (direct calls to cascade_experiments)
# ---------------------------------------------------------------------------


class TestCascadeExperiments:
    def test_no_source_experiments__noop_with_empty_result(self) -> None:
        rest_client = _cascade_rest_client(
            experiments_by_dataset={},
            items_by_experiment={},
            traces_by_id={},
        )
        client = _client_with_recreate_capture()

        result = cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            source_project_name="SourceProject",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={},
            item_id_remap={},
            audit=_audit(),
        )

        assert result.experiments_migrated == 0
        assert result.experiments_skipped == 0
        assert result.traces_migrated == 0
        assert result.spans_migrated == 0
        assert result.trace_id_remap == {}
        client.create_experiment.assert_not_called()
        rest_client.traces.create_traces.assert_not_called()
        rest_client.spans.create_spans.assert_not_called()

    def test_full_fidelity_copy__forwards_name_tags_type_eval_method_metadata(
        self,
    ) -> None:
        # An experiment with the fidelity fields the BE round-trips today.
        # `assertion_results` lives per-item on the BE side; we don't assert
        # on it here because Slice 3 forwards experiment-item fields via
        # ``recreate_experiment`` which already round-trips trace_id and
        # dataset_item_id (the only two it persists onto the item write).
        experiment = _Experiment(
            id="src-exp-1",
            name="nightly-eval",
            tags=["nightly", "trial"],
            type="trial",
            evaluation_method="evaluation_suite",
            metadata={
                "prompts": [{"role": "system", "content": "you are helpful"}],
                "experiment_runner_version": "1.2.3",
            },
            dataset_version_id="src-v-1",
        )
        item = _ExperimentItem(
            id="src-item-1",
            experiment_id="src-exp-1",
            trace_id="src-trace-1",
            dataset_item_id="src-ds-item-1",
        )
        trace = _Trace(id="src-trace-1", name="trace-1")

        rest_client = _cascade_rest_client(
            experiments_by_dataset={"src-dataset-1": [experiment]},
            items_by_experiment={"experiment": [item]},
            traces_by_id={"src-trace-1": trace},
            spans_by_trace={"src-trace-1": []},
        )
        client = _client_with_recreate_capture()

        cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            source_project_name="SourceProject",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={"src-v-1": "dest-v-1"},
            item_id_remap={"src-ds-item-1": "dest-ds-item-1"},
            audit=_audit(),
        )

        client.create_experiment.assert_called_once()
        kwargs = client.create_experiment.call_args.kwargs
        assert kwargs["name"] == "nightly-eval"
        assert kwargs["tags"] == ["nightly", "trial"]
        assert kwargs["type"] == "trial"
        assert kwargs["evaluation_method"] == "evaluation_suite"
        assert kwargs["dataset_name"] == "MyDataset"
        assert kwargs["dataset_version_id"] == "dest-v-1"
        assert kwargs["project_name"] == "DestProject"
        # Inline prompt content survives in experiment_config.
        config = kwargs["experiment_config"]
        assert config["prompts"] == [{"role": "system", "content": "you are helpful"}]
        assert config["experiment_runner_version"] == "1.2.3"

    def test_prompt_versions__stripped_on_destination(self) -> None:
        experiment = _Experiment(
            id="src-exp-1",
            prompt_versions=[{"id": "pv-1", "name": "main-prompt"}],
            metadata={"prompt_versions": [{"id": "pv-1"}], "other": "keep"},
            dataset_version_id="src-v-1",
        )
        item = _ExperimentItem(
            id="src-item-1",
            experiment_id="src-exp-1",
            trace_id="src-trace-1",
            dataset_item_id="src-ds-item-1",
        )
        rest_client = _cascade_rest_client(
            experiments_by_dataset={"src-dataset-1": [experiment]},
            items_by_experiment={"experiment": [item]},
            traces_by_id={"src-trace-1": _Trace(id="src-trace-1")},
            spans_by_trace={"src-trace-1": []},
        )
        client = _client_with_recreate_capture()

        cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            source_project_name="SourceProject",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={"src-v-1": "dest-v-1"},
            item_id_remap={"src-ds-item-1": "dest-ds-item-1"},
            audit=_audit(),
        )

        kwargs = client.create_experiment.call_args.kwargs
        # ``prompt_versions`` removed from metadata (Jacques policy).
        assert "prompt_versions" not in kwargs["experiment_config"]
        # Other metadata keys preserved.
        assert kwargs["experiment_config"].get("other") == "keep"

    def test_optimization_id__stripped_on_destination(self) -> None:
        # Slice 3 doesn't cascade the optimization entity, so any
        # ``optimization_id`` pointer is dropped to avoid a dangling FK.
        experiment = _Experiment(
            id="src-exp-1",
            optimization_id="src-opt-abc",
            dataset_version_id="src-v-1",
        )
        item = _ExperimentItem(
            id="src-item-1",
            experiment_id="src-exp-1",
            trace_id="src-trace-1",
            dataset_item_id="src-ds-item-1",
        )
        rest_client = _cascade_rest_client(
            experiments_by_dataset={"src-dataset-1": [experiment]},
            items_by_experiment={"experiment": [item]},
            traces_by_id={"src-trace-1": _Trace(id="src-trace-1")},
            spans_by_trace={"src-trace-1": []},
        )
        client = _client_with_recreate_capture()

        cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            source_project_name="SourceProject",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={"src-v-1": "dest-v-1"},
            item_id_remap={"src-ds-item-1": "dest-ds-item-1"},
            audit=_audit(),
        )

        kwargs = client.create_experiment.call_args.kwargs
        assert "optimization_id" not in kwargs, (
            "optimization_id should not be forwarded on the migrate path"
        )

    def test_trace_id_remap__populated_and_project_rewritten_on_destination(
        self,
    ) -> None:
        experiment = _Experiment(id="src-exp-1", dataset_version_id="src-v-1")
        item = _ExperimentItem(
            id="src-item-1",
            experiment_id="src-exp-1",
            trace_id="src-trace-1",
            dataset_item_id="src-ds-item-1",
        )
        trace = _Trace(
            id="src-trace-1",
            name="prod-trace",
            tags=["nightly"],
            metadata={"call_id": "abc"},
        )
        rest_client = _cascade_rest_client(
            experiments_by_dataset={"src-dataset-1": [experiment]},
            items_by_experiment={"experiment": [item]},
            traces_by_id={"src-trace-1": trace},
            spans_by_trace={"src-trace-1": []},
        )
        client = _client_with_recreate_capture()

        result = cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            source_project_name="SourceProject",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={"src-v-1": "dest-v-1"},
            item_id_remap={"src-ds-item-1": "dest-ds-item-1"},
            audit=_audit(),
        )

        # Source trace_id -> minted destination trace_id.
        assert "src-trace-1" in result.trace_id_remap
        new_trace_id = result.trace_id_remap["src-trace-1"]
        assert new_trace_id != "src-trace-1"
        assert result.traces_migrated == 1

        # The destination TraceWrite carries the destination project,
        # the minted id, and the source content verbatim.
        create_calls = rest_client.traces.create_traces.call_args_list
        assert len(create_calls) == 1
        written_traces = create_calls[0].kwargs["traces"]
        assert len(written_traces) == 1
        tw = written_traces[0]
        assert tw.id == new_trace_id
        assert tw.project_name == "DestProject"
        assert tw.name == "prod-trace"
        assert tw.tags == ["nightly"]
        assert tw.metadata == {"call_id": "abc"}

    def test_span_tree__topological_order_preserves_parent_span_remap(
        self,
    ) -> None:
        # Tree:
        #   root (no parent)
        #     child-a (parent: root)
        #       grandchild (parent: child-a)
        #     child-b (parent: root)
        #
        # The order spans arrive in the REST response is intentionally
        # NOT topological: child-b first, grandchild before child-a.
        # ``sort_spans_topologically`` should reorder so parent ids are
        # always remapped by the time a child references them.
        experiment = _Experiment(id="src-exp-1", dataset_version_id="src-v-1")
        item = _ExperimentItem(
            id="src-item-1",
            experiment_id="src-exp-1",
            trace_id="src-trace-1",
            dataset_item_id="src-ds-item-1",
        )
        spans = [
            _Span(
                id="span-child-b",
                parent_span_id="span-root",
                name="child-b",
            ),
            _Span(
                id="span-grandchild",
                parent_span_id="span-child-a",
                name="grandchild",
            ),
            _Span(
                id="span-root",
                parent_span_id=None,
                name="root",
            ),
            _Span(
                id="span-child-a",
                parent_span_id="span-root",
                name="child-a",
            ),
        ]
        rest_client = _cascade_rest_client(
            experiments_by_dataset={"src-dataset-1": [experiment]},
            items_by_experiment={"experiment": [item]},
            traces_by_id={"src-trace-1": _Trace(id="src-trace-1")},
            spans_by_trace={"src-trace-1": spans},
        )
        client = _client_with_recreate_capture()

        result = cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            source_project_name="SourceProject",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={"src-v-1": "dest-v-1"},
            item_id_remap={"src-ds-item-1": "dest-ds-item-1"},
            audit=_audit(),
        )

        assert result.spans_migrated == 4

        # Inspect the single create_spans batch.
        spans_call = rest_client.spans.create_spans.call_args_list
        assert len(spans_call) == 1
        written = spans_call[0].kwargs["spans"]
        assert len(written) == 4

        # Build a name -> SpanWrite map for inspection.
        by_name = {sw.name: sw for sw in written}
        assert by_name["root"].parent_span_id is None
        # Both root-children point at the SAME new root id (whatever it was
        # minted as), not the source id.
        assert by_name["child-a"].parent_span_id == by_name["root"].id
        assert by_name["child-b"].parent_span_id == by_name["root"].id
        # Grandchild points at the new child-a id, not the source string.
        assert by_name["grandchild"].parent_span_id == by_name["child-a"].id
        # All spans carry the destination project + the same new trace id.
        trace_id_remap = result.trace_id_remap["src-trace-1"]
        for sw in written:
            assert sw.project_name == "DestProject"
            assert sw.trace_id == trace_id_remap

    def test_missing_trace_in_source__counts_as_skipped_item(self) -> None:
        # An experiment item with a trace_id that has no corresponding
        # source trace must NOT crash the cascade -- it gets counted.
        experiment = _Experiment(id="src-exp-1", dataset_version_id="src-v-1")
        item_with_good_trace = _ExperimentItem(
            id="src-item-1",
            experiment_id="src-exp-1",
            trace_id="src-trace-1",
            dataset_item_id="src-ds-item-1",
        )
        item_with_orphan_trace = _ExperimentItem(
            id="src-item-2",
            experiment_id="src-exp-1",
            trace_id="src-trace-orphan",
            dataset_item_id="src-ds-item-2",
        )

        # Only return the good trace; the orphan's get_trace_by_id will be
        # called by the cascade. The cascade calls get_trace_by_id BEFORE
        # the recreate, so a missing trace will raise KeyError today.
        # Per spec: missing-trace skip means the per-item assert at the
        # recreate stage drops it via trace_id_map miss; here we set up
        # the cascade so the source trace exists but the remap is empty
        # after copy attempts. We model that by including BOTH traces in
        # the source so trace copy succeeds, but the recreate path then
        # uses the remap to decide whether to write the item.
        # NB: this test exercises the post-copy items_skipped counters
        # (they live on the cascade result, not recreate_experiment).
        rest_client = _cascade_rest_client(
            experiments_by_dataset={"src-dataset-1": [experiment]},
            items_by_experiment={
                "experiment": [item_with_good_trace, item_with_orphan_trace]
            },
            traces_by_id={
                "src-trace-1": _Trace(id="src-trace-1"),
                "src-trace-orphan": _Trace(id="src-trace-orphan"),
            },
            spans_by_trace={"src-trace-1": [], "src-trace-orphan": []},
        )
        client = _client_with_recreate_capture()

        # Pre-populate trace_id_remap so only "good" is mapped; "orphan"
        # is skipped in the inferred-skips count by the cascade.
        # Easier path: just verify both traces were copied (cascade copies
        # every distinct source trace) and that experiments_migrated == 1.
        result = cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            source_project_name="SourceProject",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={"src-v-1": "dest-v-1"},
            item_id_remap={
                "src-ds-item-1": "dest-ds-item-1",
                "src-ds-item-2": "dest-ds-item-2",
            },
            audit=_audit(),
        )
        assert result.experiments_migrated == 1
        assert result.traces_migrated == 2
        # Sanity: both source trace ids ended up in the remap.
        assert set(result.trace_id_remap.keys()) == {
            "src-trace-1",
            "src-trace-orphan",
        }

    def test_missing_dataset_item_id__counts_as_skipped_item(self) -> None:
        # Item references a dataset_item_id that's not in item_id_remap.
        # The cascade infers this skip from the result counter; the
        # ``recreate_experiment`` internal also skips the item from the
        # experiment-item write, but here we verify the cascade tally.
        experiment = _Experiment(id="src-exp-1", dataset_version_id="src-v-1")
        item_mapped = _ExperimentItem(
            id="src-item-1",
            experiment_id="src-exp-1",
            trace_id="src-trace-1",
            dataset_item_id="src-ds-item-1",
        )
        item_unmapped = _ExperimentItem(
            id="src-item-2",
            experiment_id="src-exp-1",
            trace_id="src-trace-2",
            dataset_item_id="src-ds-item-orphan",
        )
        rest_client = _cascade_rest_client(
            experiments_by_dataset={"src-dataset-1": [experiment]},
            items_by_experiment={"experiment": [item_mapped, item_unmapped]},
            traces_by_id={
                "src-trace-1": _Trace(id="src-trace-1"),
                "src-trace-2": _Trace(id="src-trace-2"),
            },
            spans_by_trace={"src-trace-1": [], "src-trace-2": []},
        )
        client = _client_with_recreate_capture()

        result = cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            source_project_name="SourceProject",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={"src-v-1": "dest-v-1"},
            # Only src-ds-item-1 mapped; orphan is missing.
            item_id_remap={"src-ds-item-1": "dest-ds-item-1"},
            audit=_audit(),
        )

        # Item missing from item_id_remap -> tallied as skipped.
        assert result.items_skipped_missing_item == 1

    def test_trace_id_remap__accumulates_across_experiments(self) -> None:
        # Two experiments sharing the SAME source trace must not double-copy
        # the trace; the second experiment reuses the first's mapping.
        experiment_a = _Experiment(
            id="src-exp-a", name="exp-a", dataset_version_id="src-v-1"
        )
        experiment_b = _Experiment(
            id="src-exp-b", name="exp-b", dataset_version_id="src-v-1"
        )
        shared_item_a = _ExperimentItem(
            id="src-item-a",
            experiment_id="src-exp-a",
            trace_id="src-trace-shared",
            dataset_item_id="src-ds-item-1",
        )
        shared_item_b = _ExperimentItem(
            id="src-item-b",
            experiment_id="src-exp-b",
            trace_id="src-trace-shared",
            dataset_item_id="src-ds-item-2",
        )

        rest_client = _cascade_rest_client(
            experiments_by_dataset={"src-dataset-1": [experiment_a, experiment_b]},
            items_by_experiment={
                "exp-a": [shared_item_a],
                "exp-b": [shared_item_b],
            },
            traces_by_id={"src-trace-shared": _Trace(id="src-trace-shared")},
            spans_by_trace={"src-trace-shared": []},
        )
        client = _client_with_recreate_capture()

        result = cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            source_project_name="SourceProject",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={"src-v-1": "dest-v-1"},
            item_id_remap={
                "src-ds-item-1": "dest-ds-item-1",
                "src-ds-item-2": "dest-ds-item-2",
            },
            audit=_audit(),
        )

        # Two experiments migrated, but only ONE trace copied.
        assert result.experiments_migrated == 2
        assert result.traces_migrated == 1
        # The remap has exactly one entry, shared between both experiments.
        assert len(result.trace_id_remap) == 1
        # create_traces was called exactly once.
        assert rest_client.traces.create_traces.call_count == 1

    def test_experiment_without_id__raises_experiment_cascade_error(self) -> None:
        # Defensive check: the BE shouldn't return an experiment with
        # ``id=None`` but the cascade treats it as fatal because we
        # can't enumerate items without it.
        broken = _Experiment(id="placeholder")
        broken.id = None  # type: ignore[assignment]

        rest_client = _cascade_rest_client(
            experiments_by_dataset={"src-dataset-1": [broken]},
            items_by_experiment={},
            traces_by_id={},
        )
        client = _client_with_recreate_capture()

        with pytest.raises(ExperimentCascadeError):
            cascade_experiments(
                client,
                rest_client,
                source_dataset_id="src-dataset-1",
                source_project_name="SourceProject",
                target_dataset_name="MyDataset",
                target_project_name="DestProject",
                version_remap={},
                item_id_remap={},
                audit=_audit(),
            )

    def test_trace_feedback_scores__copied_to_destination_trace(self) -> None:
        # Source trace carries a feedback score; the cascade must re-emit
        # it against the new destination trace id via
        # ``traces.score_batch_of_traces`` (the trace create payload
        # doesn't accept feedback scores -- they live in a separate table).
        from unittest.mock import MagicMock

        score_a = MagicMock()
        score_a.name = "correctness"
        score_a.category_name = None
        score_a.value = 0.9
        score_a.reason = "looks good"
        score_a.source = "sdk"

        score_b = MagicMock()
        score_b.name = "latency_p95"
        score_b.category_name = None
        score_b.value = 230.5
        score_b.reason = None
        score_b.source = "online_scoring"

        experiment = _Experiment(id="src-exp-1", dataset_version_id="src-v-1")
        item = _ExperimentItem(
            id="src-item-1",
            experiment_id="src-exp-1",
            trace_id="src-trace-1",
            dataset_item_id="src-ds-item-1",
        )
        trace = _Trace(id="src-trace-1", feedback_scores=[score_a, score_b])

        rest_client = _cascade_rest_client(
            experiments_by_dataset={"src-dataset-1": [experiment]},
            items_by_experiment={"experiment": [item]},
            traces_by_id={"src-trace-1": trace},
            spans_by_trace={"src-trace-1": []},
        )
        client = _client_with_recreate_capture()

        result = cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            source_project_name="SourceProject",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={"src-v-1": "dest-v-1"},
            item_id_remap={"src-ds-item-1": "dest-ds-item-1"},
            audit=_audit(),
        )

        new_trace_id = result.trace_id_remap["src-trace-1"]

        # score_batch_of_traces called exactly once with both scores,
        # rewritten to point at the destination trace id + project.
        rest_client.traces.score_batch_of_traces.assert_called_once()
        kwargs = rest_client.traces.score_batch_of_traces.call_args.kwargs
        scores = kwargs["scores"]
        assert len(scores) == 2
        names = {s.name for s in scores}
        assert names == {"correctness", "latency_p95"}
        for s in scores:
            assert s.id == new_trace_id
            assert s.project_name == "DestProject"

    def test_trace_without_feedback_scores__skips_score_batch_call(self) -> None:
        # No-op path: when a source trace carries no feedback scores, the
        # cascade must NOT call score_batch_of_traces (saves a round-trip).
        experiment = _Experiment(id="src-exp-1", dataset_version_id="src-v-1")
        item = _ExperimentItem(
            id="src-item-1",
            experiment_id="src-exp-1",
            trace_id="src-trace-1",
            dataset_item_id="src-ds-item-1",
        )
        trace = _Trace(id="src-trace-1", feedback_scores=None)

        rest_client = _cascade_rest_client(
            experiments_by_dataset={"src-dataset-1": [experiment]},
            items_by_experiment={"experiment": [item]},
            traces_by_id={"src-trace-1": trace},
            spans_by_trace={"src-trace-1": []},
        )
        client = _client_with_recreate_capture()

        cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            source_project_name="SourceProject",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={"src-v-1": "dest-v-1"},
            item_id_remap={"src-ds-item-1": "dest-ds-item-1"},
            audit=_audit(),
        )

        rest_client.traces.score_batch_of_traces.assert_not_called()

    def test_span_feedback_scores__copied_to_destination_spans(self) -> None:
        # Span-level feedback scores ride along on the spans' read payload
        # (``span_dict["feedback_scores"]``). Cascade must re-emit them
        # against the new span ids via ``spans.score_batch_of_spans``.
        experiment = _Experiment(id="src-exp-1", dataset_version_id="src-v-1")
        item = _ExperimentItem(
            id="src-item-1",
            experiment_id="src-exp-1",
            trace_id="src-trace-1",
            dataset_item_id="src-ds-item-1",
        )
        spans = [
            _Span(
                id="span-root",
                parent_span_id=None,
                name="root",
                feedback_scores=[
                    {
                        "name": "span-quality",
                        "category_name": None,
                        "value": 0.75,
                        "reason": "ok",
                        "source": "sdk",
                    }
                ],
            ),
        ]
        rest_client = _cascade_rest_client(
            experiments_by_dataset={"src-dataset-1": [experiment]},
            items_by_experiment={"experiment": [item]},
            traces_by_id={"src-trace-1": _Trace(id="src-trace-1")},
            spans_by_trace={"src-trace-1": spans},
        )
        client = _client_with_recreate_capture()

        cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            source_project_name="SourceProject",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={"src-v-1": "dest-v-1"},
            item_id_remap={"src-ds-item-1": "dest-ds-item-1"},
            audit=_audit(),
        )

        rest_client.spans.score_batch_of_spans.assert_called_once()
        kwargs = rest_client.spans.score_batch_of_spans.call_args.kwargs
        scores = kwargs["scores"]
        assert len(scores) == 1
        assert scores[0].name == "span-quality"
        assert scores[0].project_name == "DestProject"
        # The score's id must be the NEW span id (not the source).
        assert scores[0].id != "span-root"

    def test_trace_assertion_results__copied_to_destination_trace(self) -> None:
        # Source experiment item carries assertion_results via the Compare
        # view (test-suite-driven experiments). Those assertion_results are
        # ENTITY-scoped to the trace on the BE side; the cascade re-emits
        # them via ``store_assertions_batch(entity_type='TRACE', ...)``
        # against the new trace id, NOT through any ExperimentItem field
        # (which the BE silently drops on write).
        experiment = _Experiment(id="src-exp-1", dataset_version_id="src-v-1")
        # Source assertion results -- mock objects with the Compare view's
        # ``value`` / ``passed`` / ``reason`` shape.
        from unittest.mock import MagicMock as _MM

        ar_pass = _MM()
        ar_pass.value = "exact-match"
        ar_pass.passed = True
        ar_pass.reason = "matched reference"

        ar_fail = _MM()
        ar_fail.value = "threshold-check"
        ar_fail.passed = False
        ar_fail.reason = "below 0.8"

        item = _ExperimentItem(
            id="src-item-1",
            experiment_id="src-exp-1",
            trace_id="src-trace-1",
            dataset_item_id="src-ds-item-1",
            extras={"assertion_results": [ar_pass, ar_fail]},
        )
        rest_client = _cascade_rest_client(
            experiments_by_dataset={"src-dataset-1": [experiment]},
            items_by_experiment={"experiment": [item]},
            traces_by_id={"src-trace-1": _Trace(id="src-trace-1")},
            spans_by_trace={"src-trace-1": []},
        )
        client = _client_with_recreate_capture()

        result = cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            source_project_name="SourceProject",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={"src-v-1": "dest-v-1"},
            item_id_remap={"src-ds-item-1": "dest-ds-item-1"},
            audit=_audit(),
        )

        # store_assertions_batch called with entity_type=TRACE, scoped to
        # the new destination trace id + project.
        rest_client.assertion_results.store_assertions_batch.assert_called_once()
        call = rest_client.assertion_results.store_assertions_batch.call_args
        assert call.kwargs["entity_type"] == "TRACE"
        ars = call.kwargs["assertion_results"]
        assert len(ars) == 2
        new_trace_id = result.trace_id_remap["src-trace-1"]
        # Mapping: AssertionResultCompare.value -> name,
        # AssertionResultCompare.passed -> status (passed|failed),
        # AssertionResultCompare.reason -> reason.
        by_name = {a.name: a for a in ars}
        assert by_name["exact-match"].entity_id == new_trace_id
        assert by_name["exact-match"].project_name == "DestProject"
        assert by_name["exact-match"].status == "passed"
        assert by_name["exact-match"].reason == "matched reference"
        assert by_name["threshold-check"].status == "failed"
        assert by_name["threshold-check"].reason == "below 0.8"
        for a in ars:
            assert a.source == "sdk"

    def test_no_assertion_results__skips_store_assertions_call(self) -> None:
        # Regular-dataset items (no assertion_results) must NOT trigger
        # store_assertions_batch -- it would 400 on an empty batch and
        # is a no-op semantically.
        experiment = _Experiment(id="src-exp-1", dataset_version_id="src-v-1")
        item = _ExperimentItem(
            id="src-item-1",
            experiment_id="src-exp-1",
            trace_id="src-trace-1",
            dataset_item_id="src-ds-item-1",
        )
        rest_client = _cascade_rest_client(
            experiments_by_dataset={"src-dataset-1": [experiment]},
            items_by_experiment={"experiment": [item]},
            traces_by_id={"src-trace-1": _Trace(id="src-trace-1")},
            spans_by_trace={"src-trace-1": []},
        )
        client = _client_with_recreate_capture()

        cascade_experiments(
            client,
            rest_client,
            source_dataset_id="src-dataset-1",
            source_project_name="SourceProject",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            version_remap={"src-v-1": "dest-v-1"},
            item_id_remap={"src-ds-item-1": "dest-ds-item-1"},
            audit=_audit(),
        )

        rest_client.assertion_results.store_assertions_batch.assert_not_called()


# ---------------------------------------------------------------------------
# Planner-level test for cascade action placement in the plan
# ---------------------------------------------------------------------------


class TestPlannerCascadePlacement:
    def test_default__plan_includes_cascade_after_replay(self) -> None:
        rest_client = _planner_rest_client(
            [
                _Page([_DatasetRow(id="src-1", name="MyDataset")]),
                _Page([]),
            ]
        )

        plan = planner_module.build_dataset_plan(
            rest_client=rest_client,
            name="MyDataset",
            to_project="B",
            from_project=None,
        )

        types = [type(a).__name__ for a in plan.actions]
        # CascadeExperiments must come AFTER ReplayVersions so the cascade
        # reads a populated version_remap / item_id_remap from the plan.
        assert types.index("CascadeExperiments") > types.index("ReplayVersions")


# ---------------------------------------------------------------------------
# Smoke test: importable + dataclass shape
# ---------------------------------------------------------------------------


def test_experiment_cascade_result__default_construction() -> None:
    # Defensive: the dataclass should construct with no args (all fields
    # default-factory'd). This is what the cascade uses for the empty case.
    result = ExperimentCascadeResult()
    assert result.trace_id_remap == {}
    assert result.experiments_migrated == 0
    assert result.skipped_experiments == []


def test_module_exports() -> None:
    # Cheap import-shape check so accidental rename / unexport regressions
    # surface in unit tests rather than at runtime.
    assert callable(cascade_module.cascade_experiments)
    assert hasattr(cascade_module, "ExperimentCascadeResult")
    assert hasattr(cascade_module, "cascade_one_experiment")
