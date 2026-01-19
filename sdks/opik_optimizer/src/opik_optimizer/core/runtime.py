from __future__ import annotations

from typing import Any, TYPE_CHECKING, cast
from collections.abc import Sequence
import copy
import inspect
import logging
import math

from opik import Dataset

from ..api_objects import chat_prompt
from ..api_objects.types import MetricFunction
from ..agents import LiteLLMAgent, OptimizableAgent
from ..core.results import OptimizationResult, OptimizationRound
from ..core.state import AlgorithmResult, OptimizationContext
from ..utils.display.run import OptimizationRunDisplay
from ..utils.logging import debug_log
from .. import helpers

if TYPE_CHECKING:  # pragma: no cover
    from ..base_optimizer import BaseOptimizer


logger = logging.getLogger(__name__)


class ExperimentConfigBuilder:
    """Build experiment configuration payloads for evaluations."""

    def __init__(self, optimizer: BaseOptimizer) -> None:
        self._optimizer = optimizer

    @staticmethod
    def deep_merge_dicts(base: dict[str, Any], overrides: dict[str, Any]) -> dict[str, Any]:
        result = copy.deepcopy(base)
        for key, value in overrides.items():
            if (
                key in result
                and isinstance(result[key], dict)
                and isinstance(value, dict)
            ):
                result[key] = ExperimentConfigBuilder.deep_merge_dicts(result[key], value)
            else:
                result[key] = value
        return result

    def prepare(
        self,
        *,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None,
        validation_dataset: Dataset | None,
        experiment_config: dict[str, Any] | None,
        configuration_updates: dict[str, Any] | None,
        additional_metadata: dict[str, Any] | None,
        is_single_prompt_optimization: bool,
    ) -> dict[str, Any]:
        project_name = self._optimizer.project_name

        prompt_messages: list[dict[str, Any]] | dict[str, list[dict[str, Any]]]
        prompt_name: str | None | dict[str, str | None]
        prompt_project_name: str | None | dict[str, str | None]

        if isinstance(prompt, dict):
            first_prompt = next(iter(prompt.values()))
            agent_config = self._optimizer._build_agent_config(first_prompt)
            tool_signatures = self._optimizer._summarize_tool_signatures(first_prompt)

            if is_single_prompt_optimization:
                prompt_messages = first_prompt.get_messages()
                prompt_name = getattr(first_prompt, "name", None)
                prompt_project_name = getattr(first_prompt, "project_name", None)
            else:
                prompt_dict = cast(dict[str, chat_prompt.ChatPrompt], prompt)
                prompt_messages = {k: p.get_messages() for k, p in prompt_dict.items()}
                prompt_name = {k: getattr(p, "name", None) for k, p in prompt_dict.items()}
                prompt_project_name = {
                    k: getattr(p, "project_name", None) for k, p in prompt_dict.items()
                }

            tools = self._optimizer._serialize_tools(first_prompt)
        else:
            agent_config = self._optimizer._build_agent_config(prompt)
            tool_signatures = self._optimizer._summarize_tool_signatures(prompt)
            prompt_messages = prompt.get_messages()
            prompt_name = getattr(prompt, "name", None)
            tools = self._optimizer._serialize_tools(prompt)
            prompt_project_name = getattr(prompt, "project_name", None)

        base_config: dict[str, Any] = {
            "project_name": project_name,
            "agent_config": agent_config,
            "metric": metric.__name__,
            "dataset_training": dataset.name,
            "dataset_training_id": dataset.id,
            "optimizer": self._optimizer.__class__.__name__,
            "optimizer_metadata": self._optimizer._build_optimizer_metadata(),
            "tool_signatures": tool_signatures,
            "configuration": {
                "prompt": prompt_messages,
                "prompt_name": prompt_name,
                "tools": tools,
                "prompt_project_name": prompt_project_name,
            },
        }

        if agent is not None:
            base_config["agent"] = agent.__class__.__name__

        if configuration_updates:
            base_config["configuration"] = self.deep_merge_dicts(
                base_config["configuration"], configuration_updates
            )

        if additional_metadata:
            base_config = self.deep_merge_dicts(base_config, additional_metadata)

        if experiment_config:
            base_config = self.deep_merge_dicts(base_config, experiment_config)

        if validation_dataset:
            base_config["validation_dataset"] = validation_dataset.name
            base_config["validation_dataset_id"] = validation_dataset.id

        return helpers.drop_none(base_config)


class ResultBuilder:
    """Build OptimizationResult instances from algorithm output."""

    def __init__(self, optimizer: BaseOptimizer) -> None:
        self._optimizer = optimizer

    def select_result_prompts(
        self,
        *,
        best_prompts: dict[str, chat_prompt.ChatPrompt],
        initial_prompts: dict[str, chat_prompt.ChatPrompt],
        is_single_prompt_optimization: bool,
    ) -> tuple[Any, Any]:
        if is_single_prompt_optimization:
            return list(best_prompts.values())[0], list(initial_prompts.values())[0]
        return best_prompts, initial_prompts

    def build_early_result(self, **kwargs: Any) -> OptimizationResult:
        score = kwargs["score"]
        return OptimizationResult(
            optimizer=kwargs["optimizer_name"],
            prompt=kwargs["prompt"],
            score=score,
            metric_name=kwargs["metric_name"],
            initial_prompt=kwargs["initial_prompt"],
            initial_score=score,
            details=kwargs["details"],
            history=kwargs.get("history", []) or [],
            llm_calls=kwargs.get("llm_calls"),
            llm_calls_tools=kwargs.get("llm_calls_tools"),
            llm_cost_total=kwargs.get("llm_cost_total"),
            llm_token_usage_total=kwargs.get("llm_token_usage_total"),
            dataset_id=kwargs.get("dataset_id"),
            optimization_id=kwargs.get("optimization_id"),
        )

    def build_final_result(
        self,
        *,
        algorithm_result: AlgorithmResult,
        context: OptimizationContext,
    ) -> OptimizationResult:
        optimizer = self._optimizer
        result_prompt, initial_prompt = self.select_result_prompts(
            best_prompts=algorithm_result.best_prompts,
            initial_prompts=context.initial_prompts,
            is_single_prompt_optimization=context.is_single_prompt_optimization,
        )

        optimizer_metadata = optimizer.get_metadata(context)
        finish_reason = context.finish_reason or "completed"
        stopped_early = finish_reason != "completed"

        details = {
            "initial_score": context.baseline_score,
            "model": optimizer.model,
            "temperature": optimizer.model_parameters.get("temperature"),
            "model_parameters": dict(optimizer.model_parameters),
            "trials_completed": context.trials_completed,
            "finish_reason": finish_reason,
            "stopped_early": stopped_early,
            "stop_reason": finish_reason,
            "verbose": optimizer.verbose,
        }

        details.update(optimizer_metadata)
        details.update(algorithm_result.metadata)

        history_entries = self._coerce_history_entries(algorithm_result.history)
        if not history_entries:
            history_entries = optimizer._history_builder.get_entries()
        if not history_entries:
            history_entries = self._record_fallback_history(
                result_prompt=result_prompt,
                score=algorithm_result.best_score,
                finish_reason=finish_reason,
            )

        return OptimizationResult(
            optimizer=optimizer.__class__.__name__,
            prompt=result_prompt,
            score=algorithm_result.best_score,
            metric_name=context.metric.__name__,
            initial_prompt=initial_prompt,
            initial_score=context.baseline_score,
            details=details,
            history=history_entries,
            llm_calls=optimizer.llm_call_counter,
            llm_calls_tools=optimizer.llm_call_tools_counter,
            dataset_id=context.dataset.id,
            optimization_id=context.optimization_id,
        )

    @staticmethod
    def _coerce_history_entries(
        history: Sequence[dict[str, Any] | OptimizationRound],
    ) -> list[dict[str, Any]]:
        entries: list[dict[str, Any]] = []
        for entry in history:
            if hasattr(entry, "to_dict"):
                entries.append(entry.to_dict())
            elif isinstance(entry, dict):
                entries.append(entry)
        return entries

    def _record_fallback_history(
        self,
        *,
        result_prompt: Any,
        score: float,
        finish_reason: str,
    ) -> list[dict[str, Any]]:
        optimizer = self._optimizer
        fallback_round = optimizer.begin_round()
        optimizer.record_candidate_entry(
            prompt_or_payload=result_prompt,
            score=score,
            id="fallback",
        )
        optimizer.post_candidate(
            result_prompt,
            score=score,
            round_handle=fallback_round,
        )
        optimizer.post_round(
            fallback_round,
            best_score=score,
            best_prompt=result_prompt,
            stop_reason=finish_reason,
        )
        return optimizer._history_builder.get_entries()


class AgentFactory:
    """Resolve and build agent instances plus tool metadata."""

    def __init__(self, optimizer: BaseOptimizer) -> None:
        self._optimizer = optimizer

    def setup_agent_class(
        self, prompt: chat_prompt.ChatPrompt, agent_class: Any = None
    ) -> Any:
        if agent_class is None:
            return LiteLLMAgent
        if not issubclass(agent_class, OptimizableAgent):
            raise TypeError(
                f"agent_class must inherit from OptimizableAgent, got {agent_class.__name__}"
            )
        return agent_class

    def bind_optimizer(self, agent: OptimizableAgent) -> OptimizableAgent:
        try:
            agent.optimizer = self._optimizer  # type: ignore[attr-defined]
        except Exception:  # pragma: no cover - custom agents may forbid new attrs
            logger.debug(
                "Unable to record optimizer on agent instance of %s",
                agent.__class__.__name__,
            )
        return agent

    def instantiate(
        self,
        *args: Any,
        agent_class: type[OptimizableAgent] | None = None,
        **kwargs: Any,
    ) -> OptimizableAgent:
        resolved_class = agent_class or getattr(self._optimizer, "agent_class", None)
        if resolved_class is None:
            raise ValueError("agent_class must be provided before instantiation")
        agent = resolved_class(*args, **kwargs)
        return self.bind_optimizer(agent)

    @staticmethod
    def extract_tool_prompts(
        tools: list[dict[str, Any]] | None,
    ) -> dict[str, str] | None:
        if not tools:
            return None
        return {
            (tool.get("function", {}).get("name") or f"tool_{idx}"): tool.get(
                "function", {}
            ).get("description", "")
            for idx, tool in enumerate(tools)
        }

    @staticmethod
    def serialize_tools(prompt: chat_prompt.ChatPrompt) -> list[dict[str, Any]]:
        tools_obj = getattr(prompt, "tools", None)
        if not isinstance(tools_obj, list):
            return []

        try:
            return copy.deepcopy(cast(list[dict[str, Any]], tools_obj))
        except Exception:  # pragma: no cover - defensive
            serialized_tools: list[dict[str, Any]] = []
            for tool in tools_obj:
                if isinstance(tool, dict):
                    serialized_tools.append({k: v for k, v in tool.items() if k})
            return serialized_tools

    @staticmethod
    def describe_annotation(annotation: Any) -> str | None:
        if annotation is inspect._empty:
            return None
        if isinstance(annotation, type):
            return annotation.__name__
        return str(annotation)

    def summarize_tool_signatures(
        self, prompt: chat_prompt.ChatPrompt
    ) -> list[dict[str, Any]]:
        signatures: list[dict[str, Any]] = []
        for name, func in getattr(prompt, "function_map", {}).items():
            callable_obj = getattr(func, "__wrapped__", func)
            try:
                sig = inspect.signature(callable_obj)
            except (TypeError, ValueError):  # pragma: no cover - defensive
                signatures.append({"name": name, "signature": "unavailable"})
                continue

            params: list[dict[str, Any]] = []
            for parameter in sig.parameters.values():
                params.append(
                    helpers.drop_none(
                        {
                            "name": parameter.name,
                            "kind": parameter.kind.name,
                            "annotation": self.describe_annotation(
                                parameter.annotation
                            ),
                            "default": (
                                None
                                if parameter.default is inspect._empty
                                else parameter.default
                            ),
                        }
                    )
                )

            signatures.append(
                helpers.drop_none(
                    {
                        "name": name,
                        "parameters": params,
                        "docstring": inspect.getdoc(callable_obj),
                    }
                )
            )
        return signatures

    def build_agent_config(self, prompt: chat_prompt.ChatPrompt) -> dict[str, Any]:
        agent_config: dict[str, Any] = dict(prompt.to_dict())
        agent_config["project_name"] = getattr(prompt, "project_name", None)
        agent_config["model"] = getattr(prompt, "model", None) or self._optimizer.model
        agent_config["tools"] = self.serialize_tools(prompt)
        agent_config["optimizer"] = self._optimizer.__class__.__name__
        return helpers.drop_none(agent_config)


class DisplayHelper:
    """Handle evaluation display updates."""

    def __init__(self, optimizer: BaseOptimizer) -> None:
        self._optimizer = optimizer

    def evaluation_progress(
        self,
        *,
        context: OptimizationContext,
        prompts: dict[str, chat_prompt.ChatPrompt],
        score: float,
        prev_best_score: float | None,
    ) -> None:
        if not hasattr(self._optimizer, "_display"):
            self._optimizer._display = OptimizationRunDisplay(verbose=self._optimizer.verbose)
        self._optimizer._display.evaluation_progress(
            context=context,
            prompts=prompts,
            score=self._optimizer._coerce_score(score),
            display_info={"prev_best_score": prev_best_score},
        )


class ReporterManager:
    """Encapsulate reporter lifecycle management."""

    def __init__(self, optimizer: BaseOptimizer) -> None:
        self._optimizer = optimizer

    def set_reporter(self, reporter: Any | None) -> None:
        self._optimizer._reporter = reporter

    def clear_reporter(self) -> None:
        self._optimizer._reporter = None


class ValidationHelper:
    """Validate optimization inputs."""

    def __init__(self, optimizer: BaseOptimizer) -> None:
        self._optimizer = optimizer

    def validate_inputs(
        self,
        *,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        support_content_parts: bool,
    ) -> None:
        if isinstance(prompt, dict):
            for prompt_value in prompt.values():
                if not isinstance(prompt_value, chat_prompt.ChatPrompt):
                    raise ValueError("Prompt must be a ChatPrompt object")

            if prompt_value._has_content_parts() and not support_content_parts:
                raise ValueError(
                    "Prompt has content parts, which are not supported by this optimizer - You can use the Hierarchical Reflective Optimizer instead."
                )
        elif isinstance(prompt, chat_prompt.ChatPrompt):
            if prompt._has_content_parts() and not support_content_parts:
                raise ValueError(
                    "Prompt has content parts, which are not supported by this optimizer - You can use the Hierarchical Reflective Optimizer instead."
                )
        else:
            raise ValueError(
                "Prompt must be a ChatPrompt object or a dictionary of ChatPrompt objects"
            )

        if not isinstance(dataset, Dataset):
            raise ValueError("Dataset must be a Dataset object")

        if not callable(metric):
            raise ValueError(
                "Metric must be a callable function that takes `dataset_item` and `llm_output` as arguments, "
                "and optionally `task_span` for metrics that need access to span information."
            )


class CleanupManager:
    """Handle optimizer cleanup responsibilities."""

    def __init__(self, optimizer: BaseOptimizer) -> None:
        self._optimizer = optimizer

    def cleanup(self) -> None:
        self._optimizer._reset_counters()
        self._optimizer._history_builder.clear()
        if self._optimizer._opik_client is not None:
            self._optimizer._opik_client = None
        self._optimizer._reporter = None
        logger.debug("Cleaned up resources for %s", self._optimizer.__class__.__name__)


class OpikClientManager:
    """Lazy Opik client initialization."""

    def __init__(self, optimizer: BaseOptimizer) -> None:
        self._optimizer = optimizer

    def get_client(self) -> Any:
        if self._optimizer._opik_client is None:
            import opik

            self._optimizer._opik_client = opik.Opik()
        return self._optimizer._opik_client


class FinishReasonManager:
    """Handle early-stop checks and finish reason defaults."""

    def __init__(self, optimizer: BaseOptimizer) -> None:
        self._optimizer = optimizer

    def should_stop(self, context: OptimizationContext) -> bool:
        if context.should_stop:
            return True

        if self._optimizer.skip_perfect_score and context.current_best_score is not None:
            if context.current_best_score >= self._optimizer.perfect_score:
                context.finish_reason = context.finish_reason or "perfect_score"
                context.should_stop = True
                debug_log(
                    "early_stop",
                    reason=context.finish_reason,
                    best_score=context.current_best_score,
                    trials_completed=context.trials_completed,
                )
                return True

        if context.trials_completed >= context.max_trials:
            context.finish_reason = context.finish_reason or "max_trials"
            context.should_stop = True
            debug_log(
                "early_stop",
                reason=context.finish_reason,
                trials_completed=context.trials_completed,
                max_trials=context.max_trials,
            )
            return True

        return False

    def should_skip_optimization(
        self,
        *,
        baseline_score: float | None,
        skip_perfect_score: bool | None,
        perfect_score: float | None,
    ) -> bool:
        if baseline_score is None:
            return False
        effective_skip = (
            self._optimizer.skip_perfect_score
            if skip_perfect_score is None
            else skip_perfect_score
        )
        if not effective_skip:
            return False
        threshold = (
            self._optimizer.perfect_score
            if perfect_score is None
            else perfect_score
        )
        return baseline_score >= threshold

    @staticmethod
    def finalize_finish_reason(context: OptimizationContext) -> None:
        if context.finish_reason is None:
            if context.trials_completed >= context.max_trials:
                context.finish_reason = "max_trials"
            else:
                context.finish_reason = "completed"


class LLMUsageTracker:
    """Manage LLM usage counters for an optimizer instance."""

    def __init__(self, optimizer: BaseOptimizer) -> None:
        self._optimizer = optimizer

    def reset(self) -> None:
        self._optimizer.llm_call_counter = 0
        self._optimizer.llm_call_tools_counter = 0
        self._optimizer.llm_cost_total = 0.0
        self._optimizer.llm_token_usage_total = {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
        }

    def increment_call(self) -> None:
        self._optimizer.llm_call_counter += 1

    def increment_tool_call(self) -> None:
        self._optimizer.llm_call_tools_counter += 1

    def add_cost(self, cost: float | None) -> None:
        if cost is None:
            return
        self._optimizer.llm_cost_total += float(cost)

    def add_usage(self, usage: dict[str, Any] | None) -> None:
        if not usage:
            return
        self._optimizer.llm_token_usage_total["prompt_tokens"] += int(
            usage.get("prompt_tokens", 0)
        )
        self._optimizer.llm_token_usage_total["completion_tokens"] += int(
            usage.get("completion_tokens", 0)
        )
        self._optimizer.llm_token_usage_total["total_tokens"] += int(
            usage.get("total_tokens", 0)
        )


class ScoreCoercer:
    """Normalize scores into builtin floats."""

    @staticmethod
    def coerce(raw_score: Any) -> float:
        try:
            score = float(raw_score)
        except (TypeError, ValueError):
            raise TypeError(
                f"Score must be convertible to float, got {type(raw_score).__name__}"
            )

        if math.isnan(score):
            raise ValueError("Score cannot be NaN.")

        return score


class RuntimeServices:
    """Bundle runtime helpers for BaseOptimizer."""

    def __init__(self, optimizer: BaseOptimizer) -> None:
        self.agent_factory = AgentFactory(optimizer)
        self.result_builder = ResultBuilder(optimizer)
        self.experiment_config_builder = ExperimentConfigBuilder(optimizer)
        self.display = DisplayHelper(optimizer)
        self.reporter = ReporterManager(optimizer)
        self.validator = ValidationHelper(optimizer)
        self.finish = FinishReasonManager(optimizer)
        self.usage = LLMUsageTracker(optimizer)
        self.cleanup = CleanupManager(optimizer)
        self.opik_client = OpikClientManager(optimizer)
