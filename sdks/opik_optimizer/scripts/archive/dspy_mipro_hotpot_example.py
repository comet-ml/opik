"""
In this example, we show how to use DSPy's MIPROv2 with:

* Opik's metrics
* Opik's datasets
* Opik's optimization infrastructure
"""

import os

import dspy
from opik.evaluation.metrics.score_result import ScoreResult
from typing import Any

# Setup cache on disk:
import litellm
import opik
from litellm.caching import Cache
from opik.evaluation.metrics import Equals
from opik.integrations.dspy.callback import OpikCallback

from opik_optimizer import (
    TaskConfig,
)
from opik_optimizer.datasets import hotpot_300
from opik_optimizer.mipro_optimizer import (
    MIPROv2,
)  # Or from dspy.teleprompt import MIPROv2
from opik_optimizer.utils import search_wikipedia
from opik_optimizer.mipro_optimizer.utils import (
    create_dspy_training_set,
    opik_metric_to_dspy,
)
from opik_optimizer.utils import optimization_context
from opik_optimizer.utils import get_optimization_run_url_by_id

# Using disk cache for LLM calls
disk_cache_dir = os.path.expanduser("~/.litellm_cache")
litellm.cache = Cache(type="disk", disk_cache_dir=disk_cache_dir)
search_wikipedia = opik.track(type="tool")(search_wikipedia)

# First, we set the Opik callback for all dspy calls:
project_name = "DSPy-MIPROv2"
opik_callback = OpikCallback(project_name=project_name, log_graph=True)
dspy.settings.configure(
    lm=dspy.LM(model="openai/gpt-4o-mini"),
    callbacks=[opik_callback],
)


# This are useful methods of logging optimization data:
def equals(dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
    return Equals().score(reference=dataset_item["answer"], output=llm_output)


task_config = TaskConfig(
    instruction_prompt="Answer the question",
    input_dataset_fields=["question"],
    output_dataset_field="answer",
    tools=[search_wikipedia],
)

dataset = hotpot_300()
trainset = create_dspy_training_set(dataset.get_items(), "question", 50)
metric_function = opik_metric_to_dspy(equals, "answer")

experiment_config = {
    "optimizer": "DSPy-MIPROv2",
    "tools": [search_wikipedia.__name__],
    "metric": metric_function,
    "num_threads": 10,
    "num_candidates": 5,
    "num_trials": 5,
    "dataset": dataset.name,
}

opik_client = opik.Opik()

with optimization_context(
    client=opik_client,
    dataset_name=dataset.name,
    objective_name="equals",
    metadata={"optimizer": "DSPy-MIPROv2"},
) as optimization:
    optimizer = MIPROv2(
        metric=metric_function,
        auto=None,
        num_candidates=experiment_config["num_candidates"],
        n_threads=10,
        verbose=False,
        seed=42,
        # Add these if using Opik's MIPROv2:
        opik_dataset=dataset,
        opik_metric=equals,
        opik_prompt_task_config=task_config,
        opik_project_name=project_name,
        opik_optimization_id=optimization.id if optimization is not None else None,
        experiment_config=experiment_config,
    )

    program = dspy.ReAct("question -> answer", [search_wikipedia])

    results = optimizer.compile(
        student=program,
        trainset=trainset,
        provide_traceback=True,
        requires_permission_to_run=False,
        num_trials=experiment_config["num_trials"],
    )

    print("The optimized agent is:")
    print(results)

    # Print the link to the optimizer run
    if optimization and optimization.id:
        try:
            run_link = get_optimization_run_url_by_id(
                optimization_id=optimization.id, dataset_id=dataset.id
            )
            print(f"\nOptimizer run link: {run_link}")
        except Exception as e:
            print(f"\nCould not generate optimizer run link: {e}")
