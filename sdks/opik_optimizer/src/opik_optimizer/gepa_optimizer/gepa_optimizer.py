import logging
from typing import Any, Callable, Dict, List, Optional, Union, Tuple

import opik
from opik import Dataset
from opik.evaluation.metrics.score_result import ScoreResult

from ..base_optimizer import BaseOptimizer
from ..optimization_config import chat_prompt
from ..optimization_result import OptimizationResult
from ..utils import optimization_context
from . import reporting as gepa_reporting


logger = logging.getLogger(__name__)


class GepaOptimizer(BaseOptimizer):
    """
    Wrapper around the external GEPA package (https://github.com/gepa-ai/gepa).

    This optimizer uses GEPA's DefaultAdapter to optimize a single system prompt for
    a single-turn task. It maps an Opik dataset + metric to GEPA's expected
    train/val set format and returns an OptimizationResult compatible with Opik's SDK.
    """

    def __init__(
        self,
        model: str,
        project_name: Optional[str] = None,
        reflection_model: Optional[str] = None,
        verbose: int = 1,
        **model_kwargs: Any,
    ) -> None:
        super().__init__(model=model, verbose=verbose, **model_kwargs)
        self.project_name = project_name
        # GEPA reflection LLM; defaults to same as task model if not provided
        self.reflection_model = reflection_model or model
        self.num_threads = self.model_kwargs.pop("num_threads", 6)

    def _to_gepa_default_datainst(
        self,
        dataset_items: List[Dict[str, Any]],
        input_key: str,
        output_key: str,
    ) -> List[Dict[str, Any]]:
        """
        Convert Opik dataset items to GEPA DefaultAdapter DataInst entries.
        Each item is a dict with keys: input, answer, additional_context.
        """
        default_items: List[Dict[str, Any]] = []
        for item in dataset_items:
            addl_context: Dict[str, str] = {}
            # Try common places where context may be stored
            # tiny_test uses metadata["context"].
            meta = item.get("metadata") or {}
            if isinstance(meta, dict) and "context" in meta and isinstance(meta["context"], str):
                addl_context["context"] = meta["context"]
            elif "context" in item and isinstance(item["context"], str):
                addl_context["context"] = item["context"]

            default_items.append(
                {
                    "input": str(item.get(input_key, "")),
                    "answer": str(item.get(output_key, "")),
                    "additional_context": addl_context,
                }
            )
        return default_items

    def _call_gepa_optimize(
        self,
        seed_prompt_text: str,
        trainset: List[Dict[str, Any]],
        valset: Optional[List[Dict[str, Any]]],
        max_metric_calls: int,
        reflection_minibatch_size: int = 3,
        candidate_selection_strategy: str = "pareto",
    ):
        try:
            import gepa
            import inspect
        except Exception as e:  # pragma: no cover - only triggered if not installed
            raise ImportError(
                "gepa package is required for GepaOptimizer. Install with `pip install gepa`."
            ) from e

        # Use GEPA default adapter by passing task_lm as model string
        # reflection_lm must be provided when using DefaultAdapter
        optimize_sig = None
        try:
            optimize_sig = inspect.signature(gepa.optimize)
        except Exception:
            optimize_sig = None

        kwargs: Dict[str, Any] = dict(
            seed_candidate={"system_prompt": seed_prompt_text},
            trainset=trainset,
            valset=valset or trainset,
            task_lm=self.model,
            reflection_lm=self.reflection_model,
            candidate_selection_strategy=candidate_selection_strategy,
            reflection_minibatch_size=reflection_minibatch_size,
            max_metric_calls=max_metric_calls,
            display_progress_bar=False,
            track_best_outputs=False,
        )

        # If the installed GEPA supports a custom adapter/eval path, prefer it.
        # We try common parameter names conservatively; otherwise fallback.
        if optimize_sig and ("adapter" in optimize_sig.parameters):
            # Defer to GEPA DefaultAdapter via strings; adapter construction
            # is handled internally when an adapter instance is not provided.
            # If explicit adapter is required, caller can provide via kwargs in future.
            pass

        result = gepa.optimize(**kwargs)
        return result

    def evaluate_prompt(
        self,
        dataset: Union[str, Dataset],
        metric: Callable[[Dict[str, Any], str], ScoreResult],
        prompt: Optional[Union[chat_prompt.ChatPrompt, str, OptimizationResult]] = None,
        n_samples: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
        verbose: int = 1,
        **kwargs: Any,
    ) -> float:
        """
        Compute the score of a prompt on a subset of the dataset by delegating
        to BaseOptimizer.evaluate_prompt where possible. This is a convenience
        evaluator; GEPA's main entry point is optimize_prompt.
        """
        # Fallback to BaseOptimizer evaluation with a simple chat prompt
        if isinstance(dataset, str):
            client = opik.Opik(project_name=self.project_name)
            dataset = client.get_dataset(dataset)

        if isinstance(prompt, OptimizationResult):
            # If OptimizationResult provided, use its prompt messages
            prompt_messages = prompt.prompt
        elif isinstance(prompt, chat_prompt.ChatPrompt):
            prompt_messages = prompt.get_messages()
        elif isinstance(prompt, str):
            prompt_messages = [{"role": "system", "content": prompt}]
        else:
            raise ValueError("`prompt` must be provided for GEPA evaluation.")

        cp = chat_prompt.ChatPrompt(
            messages=prompt_messages,
            project_name=self.project_name,
            model=self.model,
            **self.model_kwargs,
        )

        score = super().evaluate_prompt(
            prompt=cp,
            dataset=dataset,
            metric=metric,
            n_threads=self.num_threads,
            verbose=verbose,
            dataset_item_ids=dataset_item_ids,
            experiment_config=experiment_config,
            n_samples=n_samples,
        )
        return score

    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: Union[str, Dataset],
        metric: Callable[[Dict[str, Any], str], ScoreResult],
        max_metric_calls: int = 30,
        reflection_minibatch_size: int = 3,
        candidate_selection_strategy: str = "pareto",
        n_samples: Optional[int] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
        **kwargs: Any,
    ) -> OptimizationResult:
        """
        Run GEPA optimization using DefaultAdapter over the given dataset and metric.

        Args:
            dataset: An Opik dataset name or object.
            metric: Opik metric function (dataset_item, llm_output) -> ScoreResult.
            task_config: TaskConfig mapping input/output fields and optional tools.
            max_metric_calls: GEPA budget for total metric calls across the run.
            reflection_minibatch_size: Batch size GEPA uses for reflective updates.
            candidate_selection_strategy: 'pareto' (default) or 'best'.
            n_samples: If provided, sub-sample the dataset for train/val.
            experiment_config: Extra metadata to record in results.
        """
        if isinstance(dataset, str):
            client = opik.Opik(project_name=self.project_name)
            dataset = client.get_dataset(dataset)

        # Prepare seed prompt and data
        seed_prompt_text = self._extract_system_text(prompt)
        input_key, output_key = self._infer_dataset_keys(dataset)

        items = dataset.get_items()
        if n_samples is not None and n_samples > 0 and n_samples < len(items):
            # deterministic sub-sample for reproducibility
            items = items[: n_samples]

        trainset = self._to_gepa_default_datainst(items, input_key, output_key)
        valset = None  # default: GEPA uses trainset if valset is None

        # Pretty header and configuration
        gepa_reporting.display_header(verbose=self.verbose)
        gepa_reporting.display_configuration(
            {
                "model": self.model,
                "reflection_model": self.reflection_model,
                "max_metric_calls": max_metric_calls,
                "reflection_minibatch_size": reflection_minibatch_size,
                "candidate_selection_strategy": candidate_selection_strategy,
                "n_samples": n_samples or "all",
            },
            verbose=self.verbose,
        )

        # Baseline evaluation using provided prompt
        with gepa_reporting.baseline_evaluation(verbose=self.verbose) as baseline:
            try:
                baseline_score = self.evaluate_prompt(
                    dataset=dataset,
                    metric=metric,
                    prompt=prompt,
                    n_samples=n_samples,
                    verbose=0,
                )
                baseline.set_score(float(baseline_score))
            except Exception:
                # Baseline is optional; continue even if it fails
                pass

        # Track optimization run in Opik
        self._opik_client = opik.Opik(project_name=self.project_name)
        with optimization_context(
            client=self._opik_client,
            dataset_name=dataset.name,
            objective_name=metric.__name__,
            metadata={"optimizer": self.__class__.__name__},
        ) as optimization:
            with gepa_reporting.start_gepa_optimization(verbose=self.verbose):
                gepa_result = self._call_gepa_optimize(
                    seed_prompt_text=seed_prompt_text,
                    trainset=trainset,
                    valset=valset,
                    max_metric_calls=max_metric_calls,
                    reflection_minibatch_size=reflection_minibatch_size,
                    candidate_selection_strategy=candidate_selection_strategy,
                )

        # Build OptimizationResult (re-score candidates with Opik metric to pick best)
        candidates: List[Dict[str, str]] = getattr(gepa_result, "candidates", []) or []
        val_scores: List[float] = list(getattr(gepa_result, "val_aggregate_scores", []))

        rescored: List[float] = []
        for i, cand in enumerate(candidates):
            cand_prompt_text = list(cand.values())[0] if cand else ""
            cp = chat_prompt.ChatPrompt(
                messages=[{"role": "system", "content": cand_prompt_text}],
                project_name=self.project_name,
                model=self.model,
                **self.model_kwargs,
            )
            try:
                s = super().evaluate_prompt(
                    prompt=cp,
                    dataset=dataset,
                    metric=metric,
                    n_threads=self.num_threads,
                    verbose=0,
                    n_samples=n_samples,
                )
            except Exception:
                s = 0.0
            rescored.append(float(s))

        # Choose best by Opik metric if we have rescored values; otherwise fallback to GEPA index
        if rescored:
            best_idx = max(range(len(rescored)), key=lambda idx: rescored[idx])
            score = rescored[best_idx]
        else:
            best_idx = getattr(gepa_result, "best_idx", 0) or 0
            score = float(val_scores[best_idx]) if val_scores else 0.0

        best_candidate = candidates[best_idx] if candidates else getattr(gepa_result, "best_candidate", {})
        best_prompt_text = list(best_candidate.values())[0] if best_candidate else seed_prompt_text

        # Build history with both GEPA and Opik rescoring where available
        history: List[Dict[str, Any]] = []
        for i, cand in enumerate(candidates):
            cand_prompt = list(cand.values())[0] if cand else ""
            gepa_s = val_scores[i] if i < len(val_scores) else None
            opik_s = rescored[i] if i < len(rescored) else None
            history.append(
                {
                    "iteration": i + 1,
                    "prompt_candidate": cand_prompt,
                    "scores": [
                        {"metric_name": f"GEPA-{metric.__name__}", "score": gepa_s},
                        {"metric_name": metric.__name__, "score": opik_s},
                    ],
                }
            )

        details: Dict[str, Any] = {
            "model": self.model,
            "temperature": self.model_kwargs.get("temperature"),
            "optimizer": self.__class__.__name__,
            "num_candidates": getattr(gepa_result, "num_candidates", None),
            "total_metric_calls": getattr(gepa_result, "total_metric_calls", None),
            "val_scores": val_scores,
            "parents": gepa_result.parents,
        }
        if experiment_config:
            details.update({"experiment": experiment_config})

        result = OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=[{"role": "system", "content": best_prompt_text}],
            score=score,
            metric_name=metric.__name__,
            details=details,
            history=history,
            llm_calls=None,  # not tracked for GEPA DefaultAdapter
        )

        gepa_reporting.display_result(result, verbose=self.verbose)
        return result

    def _extract_system_text(self, prompt: chat_prompt.ChatPrompt) -> str:
        msgs = prompt.get_messages()
        for m in msgs:
            if m.get("role") == "system":
                return str(m.get("content", "")).strip()
        # No explicit system; synthesize from first user message
        for m in msgs:
            if m.get("role") == "user":
                return f"You are a helpful assistant. Respond to: {m.get('content','')}"
        return "You are a helpful assistant."

    def _infer_dataset_keys(self, dataset: Dataset) -> Tuple[str, str]:
        """Heuristically infer input/output keys from dataset items."""
        items = dataset.get_items(1)
        if not items:
            return "text", "label"
        sample = items[0]
        # Prefer common output keys
        output_candidates = ["label", "answer", "output", "expected_output"]
        output_key = next((k for k in output_candidates if k in sample), "label")
        # Pick first non-output textual field as input
        excluded = set([output_key, "id", "metadata"])
        input_key = next((k for k in sample.keys() if k not in excluded), "text")
        return input_key, output_key
