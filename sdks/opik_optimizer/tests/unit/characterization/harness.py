"""Scenarios, optimizer factories, snapshot normalisation and golden comparison."""

from __future__ import annotations

import difflib
import json
import math
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from collections.abc import Callable

import pytest
from opik.evaluation.metrics import score_result

from opik_optimizer import (
    ChatPrompt,
    EvolutionaryOptimizer,
    FewShotBayesianOptimizer,
    GepaOptimizer,
    HierarchicalReflectiveOptimizer,
    MetaPromptOptimizer,
    OptimizableAgent,
    ParameterOptimizer,
)
from opik_optimizer.algorithms.parameter_optimizer.ops.search_ops import (
    ParameterSearchSpace,
    ParameterSpec,
)
from opik_optimizer.algorithms.parameter_optimizer.types import ParameterType

from tests.image_fixtures import RED_IMAGE
from tests.unit.characterization.fake_llm import agent_answer, content_text, count_hints
from tests.unit.fixtures.builders import make_mock_dataset

GOLDEN_DIR = Path(__file__).parent / "golden"
UPDATE_ENV = "UPDATE_GOLDEN"
MODEL = "openai/gpt-4o-mini"
SEED = 42
N_ITEMS = 6
IMAGE_DATA_URL = RED_IMAGE
TOOL_NAME = "lookup_code"
TOOL_DESCRIPTION = "Look up the code for an item."


# ---------------------------------------------------------------------------
# Dataset / metric
# ---------------------------------------------------------------------------


def dataset_items(*, with_image: bool = False) -> list[dict[str, Any]]:
    items = []
    for i in range(N_ITEMS):
        item: dict[str, Any] = {
            "id": f"item-{i}",
            "question": f"What is the code for item {i}?",
            "answer": f"A{i}",
        }
        if with_image:
            item["image"] = IMAGE_DATA_URL
        items.append(item)
    return items


def answer_match(
    dataset_item: dict[str, Any], llm_output: str
) -> score_result.ScoreResult:
    expected = dataset_item["answer"]
    ok = str(llm_output).strip() == expected
    return score_result.ScoreResult(
        name="answer_match",
        value=1.0 if ok else 0.0,
        reason="exact match" if ok else f"expected {expected}, got {llm_output!r}",
    )


answer_match.__name__ = "answer_match"


# ---------------------------------------------------------------------------
# S2 custom agent
# ---------------------------------------------------------------------------


def _system_text(prompt: ChatPrompt) -> str:
    return "\n".join(
        content_text(m.get("content"))
        for m in prompt.get_messages()
        if m.get("role") == "system"
    )


def _user_text(prompt: ChatPrompt, item: dict[str, Any]) -> str:
    return "\n".join(
        content_text(m.get("content"))
        for m in prompt.get_messages(item)
        if m.get("role") == "user"
    )


class TwoStepAgent(OptimizableAgent):
    """Deterministic two-prompt agent (planner -> answerer); never calls an LLM."""

    def __init__(self) -> None:
        super().__init__(project_name="golden-characterization")

    def step_outputs(
        self, prompts: dict[str, ChatPrompt], dataset_item: dict[str, Any]
    ) -> dict[str, str]:
        planner, answerer = prompts["planner"], prompts["answerer"]
        planner_hints = count_hints(_system_text(planner))
        plan = f"Plan ({planner_hints} hints): {_user_text(planner, dataset_item)}"
        answer = agent_answer(
            context_text=_system_text(planner) + "\n" + _system_text(answerer),
            question_text=_user_text(answerer, dataset_item),
        )
        return {"planner": plan, "answerer": answer}

    def invoke_agent(
        self,
        prompts: dict[str, ChatPrompt],
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> str:
        _ = allow_tool_use, seed
        return self.step_outputs(prompts, dataset_item)["answerer"]


# ---------------------------------------------------------------------------
# Scenarios
# ---------------------------------------------------------------------------


def lookup_code(query: str) -> str:
    return f"Lookup result for: {query}"


@dataclass
class Scenario:
    name: str
    prompt: ChatPrompt | dict[str, ChatPrompt]
    items: list[dict[str, Any]]
    agent: OptimizableAgent | None = None
    tools: bool = False
    # When True the fake optimizer LLM answers with plain-string message content
    # (as most real models do), so the optimizer's own multimodal-preservation
    # logic is what keeps (or drops) the image parts.
    flatten_multimodal: bool = False


def build_scenario(name: str) -> Scenario:
    if name == "S1_single":
        return Scenario(
            name=name,
            prompt=ChatPrompt(
                name="chat-prompt",
                system="You are a helpful assistant.",
                user="Question: {question}",
                model=MODEL,
            ),
            items=dataset_items(),
        )
    if name == "S2_multi":
        return Scenario(
            name=name,
            prompt={
                "planner": ChatPrompt(
                    name="planner",
                    system="You plan how to find the code.",
                    user="Plan for: {question}",
                    model=MODEL,
                ),
                "answerer": ChatPrompt(
                    name="answerer",
                    system="You answer with the code.",
                    user="Answer: {question}",
                    model=MODEL,
                ),
            },
            items=dataset_items(),
            agent=TwoStepAgent(),
        )
    if name == "S3_multimodal":
        return Scenario(
            name=name,
            prompt=ChatPrompt(
                name="vision-prompt",
                messages=[
                    {"role": "system", "content": "You read images and answer."},
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": "Question: {question}"},
                            {"type": "image_url", "image_url": {"url": "{image}"}},
                        ],
                    },
                ],
                model=MODEL,
            ),
            items=dataset_items(with_image=True),
            flatten_multimodal=True,
        )
    if name == "S3b_multimodal_multitext":
        # Same as S3 but the (optimizable) system message carries two text parts,
        # which exercises the "assumes at most one text part" code paths.
        return Scenario(
            name=name,
            prompt=ChatPrompt(
                name="vision-prompt",
                messages=[
                    {
                        "role": "system",
                        "content": [
                            {"type": "text", "text": "You read images and answer."},
                            {"type": "text", "text": "Reply with the code only."},
                        ],
                    },
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": "Question: {question}"},
                            {"type": "image_url", "image_url": {"url": "{image}"}},
                        ],
                    },
                ],
                model=MODEL,
            ),
            items=dataset_items(with_image=True),
            flatten_multimodal=True,
        )
    if name == "S4_tools":
        return Scenario(
            name=name,
            prompt=ChatPrompt(
                name="tool-prompt",
                system="You can use tools to answer.",
                user="Question: {question}",
                tools=[
                    {
                        "type": "function",
                        "function": {
                            "name": TOOL_NAME,
                            "description": TOOL_DESCRIPTION,
                            "parameters": {
                                "type": "object",
                                "properties": {
                                    "query": {
                                        "type": "string",
                                        "description": "The question to look up.",
                                    }
                                },
                                "required": ["query"],
                            },
                        },
                    }
                ],
                function_map={TOOL_NAME: lookup_code},
                model=MODEL,
            ),
            items=dataset_items(),
            tools=True,
        )
    raise ValueError(name)


SCENARIOS = [
    "S1_single",
    "S2_multi",
    "S3_multimodal",
    "S3b_multimodal_multitext",
    "S4_tools",
]


def base_prompt_messages(scenario: Scenario) -> dict[str, list[dict[str, Any]]]:
    if isinstance(scenario.prompt, dict):
        return {n: p.get_messages() for n, p in scenario.prompt.items()}
    return {scenario.prompt.name: scenario.prompt.get_messages()}


# ---------------------------------------------------------------------------
# Optimizer runners
# ---------------------------------------------------------------------------


FACTORIES: dict[str, Callable[[], Any]] = {
    "MetaPromptOptimizer": lambda: MetaPromptOptimizer(
        model=MODEL, prompts_per_round=2, n_threads=1, seed=SEED, verbose=0
    ),
    "EvolutionaryOptimizer": lambda: EvolutionaryOptimizer(
        model=MODEL,
        population_size=4,
        num_generations=2,
        n_threads=1,
        seed=SEED,
        verbose=0,
    ),
    "GepaOptimizer": lambda: GepaOptimizer(
        model=MODEL, n_threads=1, seed=SEED, verbose=0
    ),
    "HierarchicalReflectiveOptimizer": lambda: HierarchicalReflectiveOptimizer(
        model=MODEL, n_threads=1, max_parallel_batches=1, seed=SEED, verbose=0
    ),
    "FewShotBayesianOptimizer": lambda: FewShotBayesianOptimizer(
        model=MODEL, min_examples=1, max_examples=3, n_threads=1, seed=SEED, verbose=0
    ),
    "ParameterOptimizer": lambda: ParameterOptimizer(
        default_n_trials=6, n_threads=1, seed=SEED, verbose=0
    ),
}

MAX_TRIALS: dict[str, int] = {
    "MetaPromptOptimizer": 4,
    "EvolutionaryOptimizer": 6,
    "GepaOptimizer": 4,
    "HierarchicalReflectiveOptimizer": 3,
    "FewShotBayesianOptimizer": 4,
    "ParameterOptimizer": 6,
}


def make_optimizer(name: str) -> Any:
    return FACTORIES[name]()


def make_dataset(scenario: Scenario) -> Any:
    return make_mock_dataset(
        scenario.items, name="golden-dataset", dataset_id="ds-golden"
    )


def _tool_kwargs(optimizer: Any, scenario: Scenario) -> dict[str, Any]:
    if not scenario.tools:
        return {}
    if optimizer.supports_tool_optimization:
        return {"optimize_tools": True, "allow_tool_use": True}
    return {"allow_tool_use": True}


def run_optimizer(name: str, scenario: Scenario) -> Any:
    optimizer = make_optimizer(name)
    if name == "ParameterOptimizer":
        # ParameterOptimizer.optimize_prompt raises NotImplementedError (pinned in
        # its own test); optimize_parameter is the entry point it accepts.
        return optimizer.optimize_parameter(
            prompt=scenario.prompt,
            dataset=make_dataset(scenario),
            metric=answer_match,
            parameter_space=ParameterSearchSpace(
                parameters=[
                    ParameterSpec.model_validate(
                        {
                            "name": "temperature",
                            "distribution": ParameterType.FLOAT,
                            "low": 0.0,
                            "high": 1.0,
                        }
                    )
                ]
            ),
            agent=scenario.agent,
            max_trials=MAX_TRIALS[name],
            project_name="golden-characterization",
        )
    return optimizer.optimize_prompt(
        prompt=scenario.prompt,
        dataset=make_dataset(scenario),
        metric=answer_match,
        agent=scenario.agent,
        max_trials=MAX_TRIALS[name],
        project_name="golden-characterization",
        **_tool_kwargs(optimizer, scenario),
    )


OPTIMIZERS = list(FACTORIES)


# ---------------------------------------------------------------------------
# Snapshot normalisation
# ---------------------------------------------------------------------------

_UUID_RE = re.compile(
    r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b", re.I
)
_HEX_ADDR_RE = re.compile(r"0x[0-9a-fA-F]{6,}")
_TS_RE = re.compile(
    r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:?\d{2})?"
)


def normalize(value: Any) -> Any:
    if isinstance(value, bool) or value is None:
        return value
    if isinstance(value, float):
        if math.isnan(value):
            return "NaN"
        if math.isinf(value):
            return "Infinity" if value > 0 else "-Infinity"
        return round(value, 6)
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        value = _UUID_RE.sub("<uuid>", value)
        value = _HEX_ADDR_RE.sub("<addr>", value)
        return _TS_RE.sub("<timestamp>", value)
    if isinstance(value, dict):
        return {str(k): normalize(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [normalize(v) for v in value]
    if hasattr(value, "model_dump"):
        return normalize(value.model_dump())
    return normalize(repr(value))


def prompt_snapshot(prompt: Any) -> Any:
    if isinstance(prompt, dict):
        return {name: prompt_snapshot(p) for name, p in sorted(prompt.items())}
    if isinstance(prompt, ChatPrompt):
        return {
            "name": prompt.name,
            "messages": prompt.get_messages(),
            "tools": prompt.tools,
            "model": prompt.model,
            "model_kwargs": prompt.model_kwargs,
        }
    return repr(prompt)


def rescore_prompt(prompt: Any, scenario: Scenario) -> float:
    """Score the returned prompt directly with the fake agent, bypassing the optimizer.

    Comparing this with ``result.score`` exposes results whose reported score
    does not belong to the returned prompt.
    """
    from opik_optimizer.agents.litellm_agent import LiteLLMAgent

    if isinstance(prompt, dict):
        prompts = prompt
        agent: Any = scenario.agent or LiteLLMAgent(
            project_name="golden-characterization"
        )
    else:
        prompts = {prompt.name: prompt}
        agent = LiteLLMAgent(project_name="golden-characterization")
    values = []
    for item in scenario.items:
        output = agent.invoke_agent(
            {name: p.copy() for name, p in prompts.items()},
            dict(item),
            allow_tool_use=scenario.tools,
        )
        values.append(answer_match(item, output).value)
    return sum(values) / len(values)


def result_snapshot(result: Any, scenario: Scenario) -> dict[str, Any]:
    details = result.details or {}
    history = result.history or []
    rounds = []
    for entry in history:
        trials = entry.get("trials") or []
        rounds.append(
            {
                "round_index": entry.get("round_index"),
                "best_score": entry.get("best_score"),
                "trial_scores": [t.get("score") for t in trials if isinstance(t, dict)],
                "stop_reason": entry.get("stop_reason"),
            }
        )
    snap: dict[str, Any] = {
        "outcome": "ok",
        "optimizer": result.optimizer,
        "prompt": prompt_snapshot(result.prompt),
        "score": result.score,
        "final_prompt_rescore": rescore_prompt(result.prompt, scenario),
        "initial_score": result.initial_score,
        "metric_name": result.metric_name,
        "finish_reason": details.get("finish_reason"),
        "stopped_early": details.get("stopped_early"),
        "trials_completed": details.get("trials_completed"),
        "history_rounds": len(history),
        "history_trials": sum(len(r["trial_scores"]) for r in rounds),
        "history": rounds,
        "details_keys": sorted(details.keys()),
    }
    if isinstance(scenario.agent, TwoStepAgent) and isinstance(result.prompt, dict):
        snap["per_prompt_outputs"] = {
            item["id"]: scenario.agent.step_outputs(result.prompt, item)
            for item in scenario.items[:3]
        }
    return normalize(snap)


def exception_snapshot(exc: BaseException) -> dict[str, Any]:
    return normalize(
        {"outcome": "raises", "type": type(exc).__name__, "message": str(exc)}
    )


# ---------------------------------------------------------------------------
# Golden comparison
# ---------------------------------------------------------------------------


def golden_path(optimizer: str, scenario: str) -> Path:
    return GOLDEN_DIR / f"{optimizer}__{scenario}.json"


def _dump(data: Any) -> str:
    return json.dumps(data, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def assert_matches_golden(
    optimizer: str, scenario: str, snapshot: dict[str, Any]
) -> None:
    path = golden_path(optimizer, scenario)
    actual = _dump(snapshot)
    if os.environ.get(UPDATE_ENV, "").lower() in {"1", "true", "yes"}:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(actual, encoding="utf-8")
        return
    if not path.exists():
        pytest.fail(
            f"Golden snapshot {path.name} is missing. Run with {UPDATE_ENV}=1 to create it."
        )
    expected = path.read_text(encoding="utf-8")
    if expected != actual:
        diff = "".join(
            difflib.unified_diff(
                expected.splitlines(keepends=True),
                actual.splitlines(keepends=True),
                fromfile=f"golden/{path.name}",
                tofile="actual",
            )
        )
        pytest.fail(
            f"Characterization snapshot mismatch for {optimizer} / {scenario}.\n"
            f"If this change is intentional, re-run with {UPDATE_ENV}=1 and commit "
            f"the updated golden file.\n\n{diff}",
            pytrace=False,
        )
