import litellm
from litellm.integrations.opik.opik import OpikLogger
import os

# os.environ["OPIK_PROJECT_NAME"] = "dspy-mipro-hotpot-500"
# opik_logger = OpikLogger()
# litellm.callbacks = [opik_logger]

# Tools:
import dspy


def search_wikipedia(query: str) -> list[str]:
    """
    This agent is used to search wikipedia. It can retrieve additional details
    about a topic.
    """
    results = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")(
        query, k=3
    )
    return [x["text"] for x in results]


import dspy
from dspy.datasets import HotPotQA

dspy.configure(lm=dspy.LM("openai/gpt-4o-mini", temperature=0.0))

trainset = [
    x.with_inputs("question") for x in HotPotQA(train_seed=2024, train_size=500).train
]
react = dspy.ReAct("question -> answer", tools=[search_wikipedia])

tp = dspy.MIPROv2(metric=dspy.evaluate.answer_exact_match, auto="light", num_threads=1)
optimized_react = tp.compile(
    react, trainset=trainset, num_trials=3, requires_permission_to_run=False
)
