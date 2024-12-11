---
sidebar_label: Custom Metric
toc_max_heading_level: 4
---

# Custom Metric

Opik allows you to define your own metrics. This is useful if you have a specific metric that is not already implemented.

If you want to write an LLM as a Judge metric, you can use either the [G-Eval metric](/evaluation/metrics/g_eval.md) or create your own from scratch.

## Custom LLM as a Judge metric

### Creating a custom metric using G-Eval

[G-eval](/evaluation/metrics/g_eval.md) allows you to specify a set of criteria for your metric and it will use a Chain of Thought prompting technique to create some evaluation steps and return a score.

To use G-Eval, you will need to specify a task introduction and evaluation criteria:

```python
from opik.evaluation.metrics import GEval

metric = GEval(
    task_introduction="You are an expert judge tasked with evaluating the faithfulness of an AI-generated answer to the given context.",
    evaluation_criteria="""
        The OUTPUT must not introduce new information beyond what's provided in the CONTEXT.
        The OUTPUT must not contradict any information given in the CONTEXT.

        Return only a score between 0 and 1.
    """,
)
```

### Writing your own custom metric

To define a custom heuristic metric, you need to subclass the `BaseMetric` class and implement the `score` method and an optional `ascore` method:

```python
from typing import Any
from opik.evaluation.metrics import base_metric, score_result

class MyCustomMetric(base_metric.BaseMetric):
    def __init__(self, name: str):
        self.name = name

    def score(self, input: str, output: str, **ignored_kwargs: Any):
        # Add you logic here

        return score_result.ScoreResult(
            value=0,
            name=self.name,
            reason="Optional reason for the score"
        )
```

The `score` method should return a `ScoreResult` object. The `ascore` method is optional and can be used to compute asynchronously if needed.

:::tip
You can also return a list of `ScoreResult` objects as part of your custom metric. This is useful if you want to return multiple scores for a given input and output pair.
:::

This metric can now be used in the `evaluate` function as explained here: [Evaluating LLMs](/evaluation/evaluate_your_llm.md).

#### Example: Creating a metric with OpenAI model

You can implement your own custom metric by creating a class that subclasses the `BaseMetric` class and implements the `score` method.

```python
from opik.evaluation.metrics import base_metric, score_result
from openai import OpenAI
from typing import Any

class LLMJudgeMetric(base_metric.BaseMetric):
    def __init__(self, name: str = "Factuality check", model_name: str = "gpt-4o"):
        self.name = name
        self.llm_client = OpenAI()
        self.model_name = model_name
        self.prompt_template = """
        You are an impartial judge evaluating the following claim for factual accuracy.
        Analyze it carefully and respond with a number between 0 and 1: 1 if completely
        accurate, 0.5 if mixed accuracy, or 0 if inaccurate. The format of the your response
        should be a single number with no other text.

        Claim to evaluate: {output}
        """

    def score(self, output: str, **ignored_kwargs: Any):
        """
        Score the output of an LLM.

        Args:
            output: The output of an LLM to score.
            **ignored_kwargs: Any additional keyword arguments. This is important so that the metric can be used in the `evaluate` function.
        """
        # Construct the prompt based on the output of the LLM
        prompt = self.prompt_template.format(output=output)

        # Generate and parse the response from the LLM
        response = self.llm_client.chat.completions.create(
            model=self.model_name,
            messages=[{"role": "user", "content": prompt}]
        )
        response_score = float(response.choices[0].message.content)

        return score_result.ScoreResult(
            name=self.name,
            value=response_score,
        )
```

You can then use this metric to score your LLM outputs:

```python
metric = LLMJudgeMetric()

metric.score(output="Paris is the capital of France")
```

In this example, we used the OpenAI Python client to call the LLM. You don't have to use the OpenAI Python client, you can update the code example above to use any LLM client you have access to.

#### Example: Adding support for many all LLM providers

In order to support a wide range of LLM providers, we recommend using the `litellm` library to call your LLM. This allows you to support hundreds of models without having to maintain a custom LLM client.

Opik providers a `LitellmChatModel` class that wraps the `litellm` library and can be used in your custom metric:

```python
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import litellm_chat_model
import json
from typing import Any

class LLMJudgeMetric(base_metric.BaseMetric):
    def __init__(self, name: str = "Factuality check", model_name: str = "gpt-4o"):
        self.name = name
        self.llm_client = litellm_chat_model.LiteLLMChatModel(model_name=model_name)
        self.prompt_template = """
        You are an impartial judge evaluating the following claim for factual accuracy. Analyze it carefully
        and respond with a number between 0 and 1: 1 if completely accurate, 0.5 if mixed accuracy, or 0 if inaccurate. Then provide one brief sentence explaining your ruling. The format of the your response
        should be:

        {
            "score": <score between 0 and 1>,
            "reason": "<reason for the score>"
        }

        Claim to evaluate: {output}
        """

    def score(self, output: str, **ignored_kwargs: Any):
        """
        Score the output of an LLM.

        Args:
            output: The output of an LLM to score.
            **ignored_kwargs: Any additional keyword arguments. This is important so that the metric can be used in the `evaluate` function.
        """
        # Construct the prompt based on the output of the LLM
        prompt = self.prompt_template.format(output=output)

        # Generate and parse the response from the LLM
        response = self.llm_client.generate_string(input=prompt)
        response_dict = json.loads(response)

        return score_result.ScoreResult(
            name=self.name,
            value=response_dict["score"],
            reason=response_dict["reason"]
        )
```

You can then use this metric to score your LLM outputs:

```python
metric = LLMJudgeMetric()

metric.score(output="Paris is the capital of France")
```

#### Example: Enforcing structured outputs

In the examples above, we ask the LLM to respond with a JSON object. However as this is not enforced, it is possible that the LLM returns a non-structured response. In order to avoid this, you can use the `litellm` library to enforce a structured output. This will make our custom metric more robust and less prone to failure.

For this we define the format of the response we expect from the LLM in the `LLMJudgeResult` class and pass it to the LiteLLM client:

```python
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import litellm_chat_model
from pydantic import BaseModel
import json
from typing import Any

class LLMJudgeResult(BaseModel):
    score: int
    reason: str

class LLMJudgeMetric(base_metric.BaseMetric):
    def __init__(self, name: str = "Factuality check", model_name: str = "gpt-4o"):
        self.name = name
        self.llm_client = litellm_chat_model.LiteLLMChatModel(model_name=model_name)
        self.prompt_template = """
        You are an impartial judge evaluating the following claim for factual accuracy. Analyze it carefully and respond with a number between 0 and 1: 1 if completely accurate, 0.5 if mixed accuracy, or 0 if inaccurate. Then provide one brief sentence explaining your ruling.

        Claim to evaluate: {output}
        """

    def score(self, output: str, **ignored_kwargs: Any):
        """
        Score the output of an LLM.

        Args:
            output: The output of an LLM to score.
            **ignored_kwargs: Any additional keyword arguments. This is important so that the metric can be used in the `evaluate` function.
        """
        # Construct the prompt based on the output of the LLM
        prompt = self.prompt_template.format(output=output)

        # Generate and parse the response from the LLM
        response = self.llm_client.generate_string(input=prompt, response_format=LLMJudgeResult)
        response_dict = json.loads(response)

        return score_result.ScoreResult(
            name=self.name,
            value=response_dict["score"],
            reason=response_dict["reason"]
        )
```

Similarly to the previous example, you can then use this metric to score your LLM outputs:

```python
metric = LLMJudgeMetric()

metric.score(output="Paris is the capital of France")
```
