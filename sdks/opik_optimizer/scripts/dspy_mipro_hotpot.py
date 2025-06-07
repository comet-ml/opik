"""
In this example, we show how to use DSPy's MIPROv2 with:

* Opik's metrics
* Opik's datasets
* Opik's optimization infrastructure
"""

import random
import os

import opik
from opik.evaluation.metrics import Equals
from opik_optimizer.datasets import hotpot_300
from opik.integrations.dspy.callback import OpikCallback
from opik_optimizer.utils import optimization_context
from opik_optimizer import (
    MetricConfig,
    TaskConfig,
    from_dataset_field,
    from_llm_response_text,
)
from opik_optimizer.mipro_optimizer.utils import (
    opik_metric_to_dspy,
    create_dspy_training_set,
)
import dspy

# Setup cache on disk:
import litellm
from litellm.caching import Cache

# Using disk cache for LLM calls
disk_cache_dir = os.path.expanduser("~/.litellm_cache")
litellm.cache = Cache(type="disk", disk_cache_dir=disk_cache_dir)

# To log all data, use this:
from opik_optimizer.mipro_optimizer import MIPROv2

# Else:
# from dspy.teleprompt import MIPROv2

# First, we set the Opik callback for all dspy calls:
project_name = "DSPy-MIPROv2"
opik_callback = OpikCallback(project_name=project_name, log_graph=True)
dspy.settings.configure(
    lm=dspy.LM(model="openai/gpt-4o-mini"),
    callbacks=[opik_callback],
)


# Define our tools:
def search_wikipedia(query: str) -> list[str]:
    """
    This agent is used to search wikipedia. It can retrieve additional details
    about a topic.
    """
    results = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")(
        query, k=3
    )
    return [x["text"] for x in results]


# This are useful methods of logging optimization data:
metric_config = MetricConfig(
    metric=Equals(project_name=project_name),
    inputs={
        "output": from_llm_response_text(),
        "reference": from_dataset_field(name="answer"),
    },
)

task_config = TaskConfig(
    instruction_prompt="Answer the question",
    input_dataset_fields=["question"],
    output_dataset_field="answer",
    tools=[search_wikipedia],
)

dataset = hotpot_300()
trainset = create_dspy_training_set(dataset.get_items(), "question", 50)
metric_function = opik_metric_to_dspy(metric_config.metric, "answer")

experiment_config = {
    "optimizer": "DSPy-MIPROv2",
    "tools": [search_wikipedia.__name__],
    "metric": metric_config.metric.name,
    "num_threads": 10,
    "num_candidates": 5,
    "num_trials": 5,
    "dataset": dataset.name,
}

opik_client = opik.Opik()

with optimization_context(
    client=opik_client,
    dataset_name=dataset.name,
    objective_name=metric_config.metric.name,
    metadata={"optimizer": "DSPy-MIPROv2"},
) as optimization:
    optimizer = MIPROv2(
        metric=metric_function,
        auto=None,
        num_candidates=experiment_config["num_candidates"],
        num_threads=10,
        verbose=False,
        seed=42,
        # Add these if using Opik's MIPROv2:
        opik_dataset=dataset,
        opik_metric_config=metric_config,
        opik_prompt_task_config=task_config,
        opik_project_name=project_name,
        opik_optimization_id=optimization.id,
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
