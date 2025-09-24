from __future__ import annotations
# mypy: ignore-errors
# flake8: noqa

import os
import sys
import types
from typing import Any, Callable, Dict, List, Optional, Tuple

import pytest

# Ensure package src is on sys.path for direct invocation
_HERE = os.path.dirname(__file__)
_SRC = os.path.abspath(os.path.join(_HERE, "../../src"))
if _SRC not in sys.path:
    sys.path.insert(0, _SRC)

# Stub external optional deps that package __init__ may import
if "rapidfuzz" not in sys.modules:
    rf = types.ModuleType("rapidfuzz")
    rf_distance = types.ModuleType("rapidfuzz.distance")
    rf_distance_Indel = types.ModuleType("rapidfuzz.distance.Indel")
    sys.modules["rapidfuzz"] = rf
    sys.modules["rapidfuzz.distance"] = rf_distance
    sys.modules["rapidfuzz.distance.Indel"] = rf_distance_Indel

if "litellm" not in sys.modules:
    litellm_mod = types.ModuleType("litellm")
    litellm_mod.drop_params = True  # attribute used by BaseOptimizer
    # Provide nested integrations.opik.opik with OpikLogger
    integrations_pkg = types.ModuleType("litellm.integrations")
    opik_pkg = types.ModuleType("litellm.integrations.opik")
    opik_mod = types.ModuleType("litellm.integrations.opik.opik")

    class OpikLogger:  # pragma: no cover - logger not exercised
        pass

    opik_mod.OpikLogger = OpikLogger
    opik_pkg.opik = opik_mod
    integrations_pkg.opik = opik_pkg
    sys.modules["litellm"] = litellm_mod
    sys.modules["litellm.integrations"] = integrations_pkg
    sys.modules["litellm.integrations.opik"] = opik_pkg
    sys.modules["litellm.integrations.opik.opik"] = opik_mod
    # caching submodule
    caching_mod = types.ModuleType("litellm.caching")

    class Cache:  # pragma: no cover - stub
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            self._cfg = kwargs

        def clear(self) -> None: ...

    caching_mod.Cache = Cache  # type: ignore[attr-defined]
    sys.modules["litellm.caching"] = caching_mod
    # types.caching
    types_pkg = types.ModuleType("litellm.types")
    types_caching_mod = types.ModuleType("litellm.types.caching")

    class LiteLLMCacheType:  # pragma: no cover - stub
        DISK = "disk"

    types_caching_mod.LiteLLMCacheType = LiteLLMCacheType  # type: ignore[attr-defined]
    sys.modules["litellm.types"] = types_pkg
    sys.modules["litellm.types.caching"] = types_caching_mod

if "pyrate_limiter" not in sys.modules:
    prl = types.ModuleType("pyrate_limiter")

    class Rate:  # pragma: no cover - stub
        def __init__(self, *args: Any, **kwargs: Any) -> None: ...

    class Duration:  # pragma: no cover - stub
        SECOND = 1

    class Limiter:  # pragma: no cover - stub
        def __init__(self, *args: Any, **kwargs: Any) -> None: ...
        def try_acquire(self, key: str) -> bool:
            return True

    prl.Rate = Rate  # type: ignore[attr-defined]
    prl.Duration = Duration  # type: ignore[attr-defined]
    prl.Limiter = Limiter  # type: ignore[attr-defined]
    sys.modules["pyrate_limiter"] = prl

# Stub out evolutionary optimizer import to avoid heavy deps
if "opik_optimizer.evolutionary_optimizer.evolutionary_optimizer" not in sys.modules:
    evo_pkg = types.ModuleType("opik_optimizer.evolutionary_optimizer")
    evo_mod = types.ModuleType(
        "opik_optimizer.evolutionary_optimizer.evolutionary_optimizer"
    )

    class EvolutionaryOptimizer:  # minimal stub to satisfy __all__
        pass

    evo_mod.EvolutionaryOptimizer = EvolutionaryOptimizer
    evo_pkg.evolutionary_optimizer = evo_mod
    # Preload into sys.modules so package __init__ resolves these
    sys.modules["opik_optimizer.evolutionary_optimizer"] = evo_pkg
    sys.modules["opik_optimizer.evolutionary_optimizer.evolutionary_optimizer"] = (
        evo_mod
    )

# Stub out few_shot_bayesian_optimizer import to avoid optuna
if (
    "opik_optimizer.few_shot_bayesian_optimizer.few_shot_bayesian_optimizer"
    not in sys.modules
):
    fsb_pkg = types.ModuleType("opik_optimizer.few_shot_bayesian_optimizer")
    fsb_mod = types.ModuleType(
        "opik_optimizer.few_shot_bayesian_optimizer.few_shot_bayesian_optimizer"
    )

    class FewShotBayesianOptimizer:  # minimal stub
        pass

    fsb_mod.FewShotBayesianOptimizer = FewShotBayesianOptimizer
    fsb_pkg.FewShotBayesianOptimizer = FewShotBayesianOptimizer  # allow `from .few_shot_bayesian_optimizer import FewShotBayesianOptimizer`
    fsb_pkg.few_shot_bayesian_optimizer = fsb_mod
    sys.modules["opik_optimizer.few_shot_bayesian_optimizer"] = fsb_pkg
    sys.modules[
        "opik_optimizer.few_shot_bayesian_optimizer.few_shot_bayesian_optimizer"
    ] = fsb_mod

# Provide a minimal fake 'opik' package if missing to avoid external dependency
if "opik" not in sys.modules:
    opik_mod = types.ModuleType("opik")

    class _DummyClient:
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            pass

        def get_dataset(self, name: str) -> Any:
            raise RuntimeError("Not implemented in tests")

        def create_optimization(self, *args: Any, **kwargs: Any) -> Any:
            raise RuntimeError("optimizations unsupported in tests")

    # Expose Opik client class
    opik_mod.Opik = _DummyClient  # type: ignore[attr-defined]

    # Minimal Dataset class for type usage
    class Dataset:  # pragma: no cover - only for import compatibility
        name: str = "stub"
        id: Optional[str] = None

        def get_items(self, n: Optional[int] = None) -> List[Dict[str, Any]]:
            return []

    opik_mod.Dataset = Dataset  # type: ignore[attr-defined]

    def track(type: str = "tool") -> Any:  # pragma: no cover - stub
        def _wrap(func: Callable) -> Callable:
            def _inner(*args: Any, **kwargs: Any) -> Any:
                return func(*args, **kwargs)

            _inner.__wrapped__ = func  # mimic wrapped function for ChatPrompt
            return _inner

        return _wrap

    opik_mod.track = track  # type: ignore[attr-defined]

    # config.get_from_user_inputs
    config_pkg = types.ModuleType("opik.config")

    class _Cfg:
        url_override = "http://localhost/"

    def _get_cfg() -> Any:
        return _Cfg()

    config_pkg.get_from_user_inputs = _get_cfg  # type: ignore[attr-defined]

    class OpikConfig:  # minimal stub
        def __init__(self) -> None:
            self.is_cloud_installation = False

    config_pkg.OpikConfig = OpikConfig  # type: ignore[attr-defined]
    # attach to root opik module
    opik_mod.config = config_pkg  # type: ignore[attr-defined]

    # evaluation.metrics.score_result.ScoreResult
    eval_pkg = types.ModuleType("opik.evaluation")
    metrics_pkg = types.ModuleType("opik.evaluation.metrics")
    base_metric_mod = types.ModuleType("opik.evaluation.metrics.base_metric")

    class BaseMetric:  # pragma: no cover - stub
        def __init__(self) -> None:
            self.name = "metric"

        def score(self, llm_output: str, **kwargs: Any) -> Any:
            return ScoreResult(value=0.0)

    base_metric_mod.BaseMetric = BaseMetric  # type: ignore[attr-defined]
    score_result_mod = types.ModuleType("opik.evaluation.metrics.score_result")

    class ScoreResult:  # minimal shim
        def __init__(
            self,
            name: str = "metric",
            value: float = 0.0,
            scoring_failed: bool = False,
            metadata: Optional[Dict[str, Any]] = None,
            reason: Optional[str] = None,
        ) -> None:
            self.name = name
            self.value = float(value)
            self.scoring_failed = scoring_failed
            self.metadata = metadata or {}
            self.reason = reason

    score_result_mod.ScoreResult = ScoreResult
    metrics_pkg.base_metric = base_metric_mod
    metrics_pkg.score_result = score_result_mod
    eval_pkg.metrics = metrics_pkg

    # evaluation.models.litellm.warning_filters
    models_pkg = types.ModuleType("opik.evaluation.models")
    litellm_pkg = types.ModuleType("opik.evaluation.models.litellm")
    warning_filters_mod = types.ModuleType(
        "opik.evaluation.models.litellm.warning_filters"
    )
    opik_monitor_mod = types.ModuleType("opik.evaluation.models.litellm.opik_monitor")

    def add_warning_filters() -> None:  # pragma: no cover - trivial
        return None

    warning_filters_mod.add_warning_filters = add_warning_filters  # type: ignore[attr-defined]
    litellm_pkg.warning_filters = warning_filters_mod

    def try_add_opik_monitoring_to_params(params: Dict[str, Any]) -> Dict[str, Any]:
        return params

    opik_monitor_mod.try_add_opik_monitoring_to_params = (
        try_add_opik_monitoring_to_params  # type: ignore[attr-defined]
    )
    litellm_pkg.opik_monitor = opik_monitor_mod
    models_pkg.litellm = litellm_pkg
    eval_pkg.models = models_pkg

    # evaluation.evaluator
    evaluator_mod = types.ModuleType("opik.evaluation.evaluator")

    class _EvalResult:
        def __init__(self) -> None:
            class _TR:
                def __init__(self) -> None:
                    self.score_results = [ScoreResult(value=0.0)]

            self.test_results = [_TR()]

    def _evaluate(*args: Any, **kwargs: Any) -> Any:
        return _EvalResult()

    def _evaluate_trial(*args: Any, **kwargs: Any) -> Any:
        return _EvalResult()

    evaluator_mod.evaluate = _evaluate  # type: ignore[attr-defined]
    evaluator_mod.evaluate_optimization_trial = _evaluate_trial  # type: ignore[attr-defined]
    eval_pkg.evaluator = evaluator_mod
    report_mod = types.ModuleType("opik.evaluation.report")

    def display_experiment_results(*args: Any, **kwargs: Any) -> None: ...
    def display_experiment_link(*args: Any, **kwargs: Any) -> None: ...

    report_mod.display_experiment_results = display_experiment_results  # type: ignore[attr-defined]
    report_mod.display_experiment_link = display_experiment_link  # type: ignore[attr-defined]
    eval_pkg.report = report_mod  # type: ignore[attr-defined]
    # engine.evaluation_tasks_executor
    engine_pkg = types.ModuleType("opik.evaluation.engine")
    ete_mod = types.ModuleType("opik.evaluation.engine.evaluation_tasks_executor")

    def _tqdm(iterable: Any, desc: str, disable: bool, total: int) -> Any:
        return iterable

    ete_mod._tqdm = _tqdm  # type: ignore[attr-defined]
    # Bind to evaluation package for attribute access
    engine_pkg.evaluation_tasks_executor = ete_mod  # type: ignore[attr-defined]
    eval_pkg.engine = engine_pkg  # type: ignore[attr-defined]
    sys.modules["opik.evaluation.engine"] = engine_pkg
    sys.modules["opik.evaluation.engine.evaluation_tasks_executor"] = ete_mod

    # Attach evaluation tree to root opik module
    opik_mod.evaluation = eval_pkg  # type: ignore[attr-defined]
    sys.modules["opik"] = opik_mod
    sys.modules["opik.config"] = config_pkg
    sys.modules["opik.evaluation"] = eval_pkg
    sys.modules["opik.evaluation.metrics"] = metrics_pkg
    sys.modules["opik.evaluation.metrics.base_metric"] = base_metric_mod
    sys.modules["opik.evaluation.metrics.score_result"] = score_result_mod
    sys.modules["opik.evaluation.models"] = models_pkg
    sys.modules["opik.evaluation.models.litellm"] = litellm_pkg
    sys.modules["opik.evaluation.models.litellm.warning_filters"] = warning_filters_mod
    sys.modules["opik.evaluation.models.litellm.opik_monitor"] = opik_monitor_mod
    sys.modules["opik.evaluation.evaluator"] = evaluator_mod
    sys.modules["opik.evaluation.report"] = report_mod
    # Additional submodules referenced by optimizer
    opik_context_mod = types.ModuleType("opik.opik_context")

    def get_current_span_data() -> Any:  # pragma: no cover - not used
        return None

    opik_context_mod.get_current_span_data = get_current_span_data  # type: ignore[attr-defined]
    sys.modules["opik.opik_context"] = opik_context_mod
    rest_api_core_mod = types.ModuleType("opik.rest_api.core")

    class ApiError(Exception): ...

    rest_api_core_mod.ApiError = ApiError  # type: ignore[attr-defined]
    sys.modules["opik.rest_api.core"] = rest_api_core_mod
    api_objects_pkg = types.ModuleType("opik.api_objects")
    optimization_mod = types.ModuleType("opik.api_objects.optimization")
    opik_client_mod = types.ModuleType("opik.api_objects.opik_client")

    class Optimization:  # pragma: no cover - stub
        def update(self, *args: Any, **kwargs: Any) -> None: ...

    optimization_mod.Optimization = Optimization  # type: ignore[attr-defined]
    opik_client_mod.Opik = _DummyClient  # type: ignore[attr-defined]
    sys.modules["opik.api_objects"] = api_objects_pkg
    sys.modules["opik.api_objects.optimization"] = optimization_mod
    sys.modules["opik.api_objects.opik_client"] = opik_client_mod
    # environment utilities
    env_mod = types.ModuleType("opik.environment")

    def get_tqdm_for_current_environment() -> Any:  # pragma: no cover - stub
        def _tqdm(x: Any) -> Any:
            return x

        return _tqdm

    env_mod.get_tqdm_for_current_environment = get_tqdm_for_current_environment  # type: ignore[attr-defined]
    sys.modules["opik.environment"] = env_mod

    # Minimal 'rich' stubs for OptimizationResult import
    if "rich" not in sys.modules:
        rich_mod = types.ModuleType("rich")
        table_mod = types.ModuleType("rich.table")
        panel_mod = types.ModuleType("rich.panel")
        console_mod = types.ModuleType("rich.console")
        text_mod = types.ModuleType("rich.text")
        box_mod = types.ModuleType("rich.box")
        progress_mod = types.ModuleType("rich.progress")
        logging_mod = types.ModuleType("rich.logging")

        class Table:
            @classmethod
            def grid(cls, *args: Any, **kwargs: Any) -> "Table":
                return cls()

            def add_column(self, *args: Any, **kwargs: Any) -> None: ...
            def add_row(self, *args: Any, **kwargs: Any) -> None: ...

        class Panel:
            def __init__(self, *args: Any, **kwargs: Any) -> None: ...

        def Group(*args: Any, **kwargs: Any) -> Any:
            return tuple(args)

        class Text:
            def __init__(
                self,
                text: str = "",
                style: Optional[str] = None,
                overflow: Optional[str] = None,
            ) -> None:
                self._text = str(text)

            def __str__(self) -> str:
                return self._text

            def __add__(self, other: Any) -> "Text":
                return Text(self._text + str(other))

            def __len__(self) -> int:
                return len(self._text)

            def append(self, other: Any) -> "Text":
                return self

            def stylize(self, *args: Any, **kwargs: Any) -> None:
                return None

            @staticmethod
            def from_ansi(s: str) -> "Text":
                return Text(s)

            @staticmethod
            def assemble(*parts: Any) -> "Text":
                buf = []
                for p in parts:
                    if isinstance(p, tuple):
                        buf.append(str(p[0]))
                    else:
                        buf.append(str(p))
                return Text("".join(buf))

        class _Box:
            DOUBLE_EDGE = object()
            ROUNDED = object()

        class _Capture:
            def __init__(self) -> None:
                self._buf: List[str] = []

            def __enter__(self) -> "_Capture":
                return self

            def __exit__(self, *args: Any) -> None:
                return None

            def write(self, s: str) -> None:
                self._buf.append(s)

            def get(self) -> str:
                return "".join(self._buf)

        class Console:
            def __init__(self, *args: Any, **kwargs: Any) -> None:
                self.is_jupyter = False

            def print(self, *args: Any, **kwargs: Any) -> None: ...
            def capture(self) -> _Capture:
                return _Capture()

        table_mod.Table = Table  # type: ignore[attr-defined]
        panel_mod.Panel = Panel  # type: ignore[attr-defined]
        console_mod.Group = Group  # type: ignore[attr-defined]
        console_mod.Console = Console  # type: ignore[attr-defined]
        text_mod.Text = Text  # type: ignore[attr-defined]
        box_mod.DOUBLE_EDGE = _Box.DOUBLE_EDGE  # type: ignore[attr-defined]
        box_mod.ROUNDED = _Box.ROUNDED  # type: ignore[attr-defined]

        # Attach submodules as attributes
        rich_mod.table = table_mod  # type: ignore[attr-defined]
        rich_mod.panel = panel_mod  # type: ignore[attr-defined]
        rich_mod.console = console_mod  # type: ignore[attr-defined]
        rich_mod.text = text_mod  # type: ignore[attr-defined]
        rich_mod.box = box_mod  # type: ignore[attr-defined]
        sys.modules["rich"] = rich_mod
        sys.modules["rich.table"] = table_mod
        sys.modules["rich.panel"] = panel_mod
        sys.modules["rich.console"] = console_mod
        sys.modules["rich.text"] = text_mod
        sys.modules["rich.box"] = box_mod
        progress_mod.track = lambda iterable, **kw: iterable  # type: ignore[attr-defined]
        sys.modules["rich.progress"] = progress_mod

        class RichHandler:  # pragma: no cover
            def __init__(self, *args: Any, **kwargs: Any) -> None:
                self.level = 0

            def handle(self, record: Any) -> None: ...

        logging_mod.RichHandler = RichHandler  # type: ignore[attr-defined]
        sys.modules["rich.logging"] = logging_mod

from opik_optimizer.gepa_optimizer import GepaOptimizer
from opik_optimizer.gepa_optimizer.adapter import (
    build_protocol_adapter,
    make_opik_eval_fn,
)
from opik_optimizer.optimization_config.chat_prompt import ChatPrompt


# -----------------------------
# Helpers and fakes
# -----------------------------


class FakeDataset:
    def __init__(self, items: List[Dict[str, Any]], name: str = "fake-ds") -> None:
        self._items = [{"id": str(i + 1), **it} for i, it in enumerate(items)]
        self.name = name
        self.id = f"ds-{name}"

    def get_items(self, n: Optional[int] = None) -> List[Dict[str, Any]]:
        if n is None:
            return list(self._items)
        return list(self._items)[:n]


def make_fake_gepa(
    accept_adapter: bool = True,
    has_default_adapter: bool = True,
) -> Tuple[types.ModuleType, Any]:
    """Create a fake `gepa` module with an `optimize` entry point and optionally a DefaultAdapter.

    Returns the module and a dict to capture calls.
    """
    calls: Dict[str, Any] = {"optimize_kwargs": None}
    gepa = types.ModuleType("gepa")

    # Optional DefaultAdapter path(s)
    if has_default_adapter:
        adapters_pkg = types.ModuleType("gepa.adapters")
        default_pkg = types.ModuleType("gepa.adapters.default")

        class DefaultAdapter:
            def __init__(
                self, model: Optional[str] = None, *args: Any, **kwargs: Any
            ) -> None:
                self.model = model

            # Simulate GEPA DefaultAdapter API returning an EvaluationBatch-like object
            def evaluate(
                self,
                batch: List[Dict[str, Any]],
                candidate: Any,
                *args: Any,
                **kwargs: Any,
            ) -> Any:
                class EvaluationBatch:
                    def __init__(self, outputs: List[str], scores: List[float]) -> None:
                        self.outputs = outputs
                        self.scores = scores

                return EvaluationBatch(
                    outputs=["out"] * len(batch), scores=[0.0] * len(batch)
                )

        default_pkg.DefaultAdapter = DefaultAdapter
        adapters_pkg.default = default_pkg
        sys.modules["gepa.adapters"] = adapters_pkg
        sys.modules["gepa.adapters.default"] = default_pkg

        # Also expose newer path (optional)
        daa_pkg = types.ModuleType("gepa.adapters.default_adapter")
        da_mod = types.ModuleType("gepa.adapters.default_adapter.default_adapter")
        da_mod.DefaultAdapter = DefaultAdapter
        daa_pkg.default_adapter = da_mod
        sys.modules["gepa.adapters.default_adapter"] = daa_pkg
        sys.modules["gepa.adapters.default_adapter.default_adapter"] = da_mod

    # Provide core adapter types
    core_pkg = types.ModuleType("gepa.core")
    core_adapter_mod = types.ModuleType("gepa.core.adapter")

    class EvaluationBatch:  # pragma: no cover - adapter shim in code covers missing type
        def __init__(
            self,
            outputs: List[str],
            scores: List[float],
            trajectories: Optional[List[Dict[str, Any]]] = None,
        ) -> None:
            self.outputs = outputs
            self.scores = scores
            self.trajectories = trajectories

    core_adapter_mod.EvaluationBatch = EvaluationBatch
    core_pkg.adapter = core_adapter_mod
    sys.modules["gepa.core"] = core_pkg
    sys.modules["gepa.core.adapter"] = core_adapter_mod

    def optimize(
        seed_candidate: Dict[str, str],
        trainset: List[Dict[str, Any]],
        valset: Optional[List[Dict[str, Any]]],
        task_lm: Optional[str],
        reflection_lm: Optional[str],
        candidate_selection_strategy: str,
        reflection_minibatch_size: int,
        max_metric_calls: int,
        display_progress_bar: bool,
        track_best_outputs: bool,
        adapter: Optional[Any] = None,
        eval_fn: Optional[Callable[[Any], float]] = None,
        score_fn: Optional[Callable[[Any], float]] = None,
        objective_fn: Optional[Callable[[Any], float]] = None,
        metric_fn: Optional[Callable[[Any], float]] = None,
        scorer: Optional[Callable[[Any], float]] = None,
        **kwargs: Any,
    ) -> Any:
        calls["optimize_kwargs"] = {
            "adapter": adapter,
            "eval_fn": eval_fn or score_fn or objective_fn or metric_fn or scorer,
            "trainset": trainset,
        }
        # Optionally exercise the adapter to emulate live-metric usage
        adapter = adapter if accept_adapter else None
        if adapter is not None:
            batch = [
                {"input": it.get("input", ""), "answer": it.get("answer", "")}
                for it in trainset
            ][:2]
            try:
                # capture_traces to populate trajectories
                adapter.evaluate(batch, seed_candidate, capture_traces=True)
            except Exception:
                pass

        class Result:
            # Two candidates; GEPA's own aggregate scores set arbitrarily
            candidates = [
                {"system_prompt": "seed sys"},
                {"system_prompt": "improved sys"},
            ]
            val_aggregate_scores = [0.25, 0.35]
            parents = [None, 0]
            num_candidates = 2
            total_metric_calls = 4
            best_idx = 1

        return Result()

    gepa.optimize = optimize  # type: ignore[attr-defined]
    sys.modules["gepa"] = gepa
    return gepa, calls


def simple_metric(dataset_item: Dict[str, Any], llm_output: str) -> float:
    # Score is 1.0 if the expected answer is a substring of the output, else 0.0
    expected = str(dataset_item.get("answer") or dataset_item.get("label") or "")
    return float(expected in (llm_output or ""))


# -----------------------------
# Adapter tests
# -----------------------------


def test_protocol_adapter_evaluate_and_reflective_dataset(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Ensure adapter uses a simple stub agent that returns "A"
    from opik_optimizer.gepa_optimizer import adapter as adapter_mod  # type: ignore

    monkeypatch.setattr(
        adapter_mod,
        "create_litellm_agent_class",
        lambda prompt: type(
            "StubAgent",
            (),
            {"__init__": lambda self, p: None, "invoke": lambda self, msgs: "A"},
        ),
    )
    # Seed prompt with user placeholder
    prompt = ChatPrompt(
        messages=[
            {"role": "system", "content": "seed sys"},
            {"role": "user", "content": "Q: {input}"},
        ],
        # Deterministic invoke always returns "A" simulating correct answer for one item
        invoke=lambda model, messages, tools, **kw: "A",
        model="openai/gpt-4o-mini",
    )
    opt = GepaOptimizer(
        model="openai/gpt-4o-mini",
        reflection_model="openai/gpt-4o",
        project_name="proj",
    )

    ds_items = [
        {"input": "x1", "answer": "A"},
        {"input": "x2", "answer": "B"},
    ]
    ds = FakeDataset(ds_items)

    adapter = build_protocol_adapter(
        base_prompt=prompt,
        optimizer=opt,
        dataset=ds,  # type: ignore[arg-type]
        metric=simple_metric,
        n_samples=None,
        optimization_id="opt-1",
    )
    assert adapter is not None
    assert getattr(adapter, "_is_opik_protocol_adapter", False) is True

    batch = ds_items
    eval_batch = adapter.evaluate(
        batch, {"system_prompt": "seed sys"}, capture_traces=True
    )
    assert hasattr(eval_batch, "outputs") and hasattr(eval_batch, "scores")
    assert len(eval_batch.outputs) == 2 and len(eval_batch.scores) == 2
    # Since invoke always returns "A": first item scores 1, second scores 0
    assert eval_batch.scores == [1.0, 0.0]

    reflective = adapter.make_reflective_dataset(
        {"system_prompt": "seed sys"}, eval_batch, ["system_prompt"]
    )  # type: ignore[arg-type]
    assert "system_prompt" in reflective
    assert len(reflective["system_prompt"]) == 2


def test_make_opik_eval_fn_prefers_logged_eval(monkeypatch: pytest.MonkeyPatch) -> None:
    class DummyOpt:
        model = "m"
        model_kwargs: Dict[str, Any] = {}
        project_name = "p"
        _gepa_live_metric_calls = 0

        def _evaluate_prompt_logged(self, **kwargs: Any) -> float:
            self._gepa_live_metric_calls += 1
            prompt = kwargs.get("prompt")
            assert prompt is not None
            # Ensure user message is preserved for dataset substitution
            msgs = prompt.get_messages({"input": "world"})
            assert any(
                msg.get("role") == "user" and "world" in msg.get("content", "")
                for msg in msgs
            )
            return 5.0

        def evaluate_prompt(self, **kwargs: Any) -> float:
            return 0.0

    ds = FakeDataset([{"input": "x", "answer": "y"}])
    seed_prompt = ChatPrompt(system="seed", user="Hello {input}")
    eval_fn = make_opik_eval_fn(
        DummyOpt(),
        ds,
        simple_metric,
        n_samples=None,
        optimization_id="opt",
        base_prompt=seed_prompt,
    )
    got = eval_fn({"system_prompt": "hi"})
    assert got == 5.0


# -----------------------------
# _call_gepa_optimize and flow tests
# -----------------------------


def test_call_gepa_optimize_uses_protocol_adapter_and_sets_live_metric(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    gepa_mod, calls = make_fake_gepa(accept_adapter=True, has_default_adapter=True)

    # Use trace mode to trigger live metric calls inside adapter.evaluate
    monkeypatch.setenv("OPIK_GEPA_TRACE_EVAL", "1")

    items = [
        {"input": "i1", "answer": "A"},
        {"input": "i2", "answer": "B"},
        {"input": "i3", "answer": "C"},
    ]
    ds = FakeDataset(items)

    # Prompt whose invoke returns "A" always
    prompt = ChatPrompt(
        messages=[
            {"role": "system", "content": "seed sys"},
            {"role": "user", "content": "Q: {input}"},
        ],
        invoke=lambda model, messages, tools, **kw: "A",
        model="openai/gpt-4o-mini",
    )

    # Patch GepaOptimizer._evaluate_prompt_logged to a deterministic scorer
    def _logged(self: GepaOptimizer, **kwargs: Any) -> float:  # type: ignore[no-redef]
        # Score equals number of items whose answer equals "A"
        n = len(ds.get_items(kwargs.get("n_samples")))
        return float(
            sum(1 for it in ds.get_items()[: n or None] if it.get("answer") == "A")
        )

    monkeypatch.setattr(GepaOptimizer, "_evaluate_prompt_logged", _logged)

    opt = GepaOptimizer(
        model="openai/gpt-4o-mini",
        reflection_model="openai/gpt-4o",
        project_name="proj",
    )
    res = opt.optimize_prompt(prompt=prompt, dataset=ds, metric=simple_metric)

    # Confirm adapter/eval_fn path used (optimize received scoring injection)
    assert calls["optimize_kwargs"] is not None
    assert (
        calls["optimize_kwargs"].get("adapter") is not None
        or calls["optimize_kwargs"].get("eval_fn") is not None
    )

    # Details should indicate live metric used via counter increment
    assert res.details.get("gepa_live_metric_used") is True
    # At least one per-example scored (since trace mode and fake optimize calls adapter.evaluate)
    assert res.details.get("gepa_live_metric_call_count", 0) >= 1


def test_require_protocol_adapter_raises_when_unavailable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Prepare fake gepa that would accept adapter, but we force protocol adapter build to None
    _, calls = make_fake_gepa(accept_adapter=True, has_default_adapter=True)
    monkeypatch.setenv("OPIK_GEPA_REQUIRE_PROTOCOL", "1")

    # Force protocol adapter failure
    monkeypatch.setattr(
        "opik_optimizer.gepa_optimizer.gepa_optimizer.build_protocol_adapter",
        lambda *a, **k: None,
    )

    items = [{"input": "i1", "answer": "A"}]
    ds = FakeDataset(items)
    prompt = ChatPrompt(
        messages=[
            {"role": "system", "content": "seed"},
            {"role": "user", "content": "Q: {input}"},
        ],
        model="m",
    )

    opt = GepaOptimizer(model="m", reflection_model="m")
    with pytest.raises(RuntimeError):
        opt.optimize_prompt(prompt=prompt, dataset=ds, metric=simple_metric)


def test_default_adapter_patch_fallback_path(monkeypatch: pytest.MonkeyPatch) -> None:
    # Fake gepa with DefaultAdapter available but we disable protocol adapter
    _, calls = make_fake_gepa(accept_adapter=True, has_default_adapter=True)
    monkeypatch.setattr(
        "opik_optimizer.gepa_optimizer.gepa_optimizer.build_protocol_adapter",
        lambda *a, **k: None,
    )
    # Ensure require flag not set
    monkeypatch.delenv("OPIK_GEPA_REQUIRE_PROTOCOL", raising=False)

    items = [{"input": "i1", "answer": "A"}]
    ds = FakeDataset(items)
    prompt = ChatPrompt(
        messages=[
            {"role": "system", "content": "seed"},
            {"role": "user", "content": "Q: {input}"},
        ],
        model="m",
    )

    opt = GepaOptimizer(model="m", reflection_model="m")
    res = opt.optimize_prompt(prompt=prompt, dataset=ds, metric=simple_metric)
    adapter = calls["optimize_kwargs"]["adapter"]
    assert getattr(adapter, "_is_opik_protocol_adapter", False) is False
    # Either subclass or attr patch; verify kind marker present
    assert getattr(adapter, "_opik_adapter_kind", None) in {
        "default_patched_subclass",
        "default_patched_attrs",
    }


def test_rescoring_overrides_gepa_best_index(monkeypatch: pytest.MonkeyPatch) -> None:
    # Fake gepa with two candidates; we'll make rescoring prefer index 0
    make_fake_gepa(accept_adapter=False, has_default_adapter=True)

    ds = FakeDataset(
        [
            {"input": "i1", "answer": "A"},
        ]
    )

    # Prompt with invoke that returns "A" if system prompt contains 'seed', else returns empty
    def invoke(
        model: str, messages: List[Dict[str, str]], tools: Any, **kw: Any
    ) -> str:
        sys_txt = next((m["content"] for m in messages if m["role"] == "system"), "")
        return "A" if "seed" in sys_txt else ""

    prompt = ChatPrompt(
        messages=[
            {"role": "system", "content": "seed sys"},
            {"role": "user", "content": "Q: {input}"},
        ],
        invoke=invoke,
        model="m",
    )

    # Logged evaluation: simply apply metric to a single dataset item using the prompt's invoke logic
    def _logged(
        self: GepaOptimizer,
        prompt: ChatPrompt,
        dataset: FakeDataset,
        metric: Callable,
        n_samples: Optional[int],
        **kw: Any,
    ) -> float:  # type: ignore[no-redef]
        items = dataset.get_items(n_samples)
        agent_output = prompt.invoke(
            prompt.model,
            prompt.get_messages(items[0]),
            prompt.tools,
            **prompt.model_kwargs,
        )  # type: ignore[misc]
        return float(metric(items[0], agent_output))

    monkeypatch.setattr(GepaOptimizer, "_evaluate_prompt_logged", _logged)

    opt = GepaOptimizer(model="m", reflection_model="m")
    res = opt.optimize_prompt(prompt=prompt, dataset=ds, metric=simple_metric)
    # Verify rescoring path executed per candidate via our patched logger
    call_counter: List[int] = []

    def _logged(
        self: GepaOptimizer,
        prompt: ChatPrompt,
        dataset: FakeDataset,
        metric: Callable,
        n_samples: Optional[int],
        **kw: Any,
    ) -> float:  # type: ignore[no-redef]
        call_counter.append(1)
        items = dataset.get_items(n_samples)
        agent_output = prompt.invoke(
            prompt.model,
            prompt.get_messages(items[0]),
            prompt.tools,
            **prompt.model_kwargs,
        )  # type: ignore[misc]
        return float(metric(items[0], agent_output))

    monkeypatch.setattr(GepaOptimizer, "_evaluate_prompt_logged", _logged)
    _ = opt.optimize_prompt(prompt=prompt, dataset=ds, metric=simple_metric)
    assert len(call_counter) >= 2  # rescored both candidates
    assert len(_.history) >= 2 and all("scores" in h for h in _.history)


def test_adapter_metric_exception_handling(monkeypatch: pytest.MonkeyPatch) -> None:
    prompt = ChatPrompt(
        messages=[
            {"role": "system", "content": "seed sys"},
            {"role": "user", "content": "Q: {input}"},
        ],
        invoke=lambda model, messages, tools, **kw: "anything",
        model="m",
    )
    opt = GepaOptimizer(model="m", reflection_model="m")
    ds_items = [{"input": "x", "answer": "A"}]
    ds = FakeDataset(ds_items)

    def bad_metric(dataset_item: Dict[str, Any], llm_output: str) -> float:
        raise RuntimeError("boom")

    adapter = build_protocol_adapter(prompt, opt, ds, bad_metric)
    assert adapter is not None
    # Non-traced mode
    eval_batch = adapter.evaluate(
        ds_items, {"system_prompt": "seed sys"}, capture_traces=False
    )
    assert eval_batch.scores == [0.0]
    # Traced mode
    monkeypatch.setenv("OPIK_GEPA_TRACE_EVAL", "1")
    eval_batch2 = adapter.evaluate(
        ds_items, {"system_prompt": "seed sys"}, capture_traces=True
    )
    assert eval_batch2.scores == [0.0]
    # Ensure adapter uses a simple stub agent that returns "A"
    from opik_optimizer.gepa_optimizer import adapter as adapter_mod  # type: ignore

    monkeypatch.setattr(
        adapter_mod,
        "create_litellm_agent_class",
        lambda prompt: type(
            "StubAgent",
            (),
            {"__init__": lambda self, p: None, "invoke": lambda self, msgs: "A"},
        ),
    )
