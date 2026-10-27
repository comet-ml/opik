"""Scenarios and optimizer factories shared by the characterization tests."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from collections.abc import Callable

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
        super().__init__(project_name="characterization")

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
        scenario.items,
        name="characterization-dataset",
        dataset_id="ds-characterization",
    )


def _tool_kwargs(optimizer: Any, scenario: Scenario) -> dict[str, Any]:
    if not scenario.tools:
        return {}
    if optimizer.supports_tool_optimization:
        return {"optimize_tools": True, "allow_tool_use": True}
    return {"allow_tool_use": True}


def run_optimizer(
    name: str,
    scenario: Scenario,
    *,
    optimizer: Any = None,
    metric: Callable[..., Any] = answer_match,
    **kwargs: Any,
) -> Any:
    """Run ``name`` on ``scenario`` through its public entrypoint."""
    optimizer = optimizer or make_optimizer(name)
    kwargs.setdefault("max_trials", MAX_TRIALS[name])
    kwargs.setdefault("project_name", "characterization")
    common = dict(
        prompt=scenario.prompt,
        dataset=kwargs.pop("dataset", None) or make_dataset(scenario),
        metric=metric,
        agent=scenario.agent,
        **kwargs,
    )
    if name == "ParameterOptimizer":
        # ParameterOptimizer only searches model settings, via optimize_parameter.
        return optimizer.optimize_parameter(
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
            **common,
        )
    return optimizer.optimize_prompt(**common, **_tool_kwargs(optimizer, scenario))


OPTIMIZERS = list(FACTORIES)
