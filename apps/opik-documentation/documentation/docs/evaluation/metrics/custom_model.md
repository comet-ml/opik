---
sidebar_label: Customize models for LLM as a Judge metrics
toc_max_heading_level: 4
---

# Customize models for LLM as a Judge metrics

Opik provides a set of LLM as a Judge metrics that are designed to be model-agnostic and can be used with any LLM. In order to achieve this, we use the [LiteLLM library](https://github.com/BerriAI/litellm) to abstract the LLM calls.

By default, Opik will use the `gpt-4o` model. However, you can change this by setting the `model` parameter when initializing your metric to any model supported by [LiteLLM](https://docs.litellm.ai/docs/providers):

```python
from opik.evaluation.metrics import Hallucination

hallucination_metric = Hallucination(
    model="gpt-4-turbo"
)
```

## Using a model supported by LiteLLM

In order to use many models supported by LiteLLM, you also need to pass additional parameters. For this, you can use the [LiteLLMChatModel](https://www.comet.com/docs/opik/python-sdk-reference/Objects/LiteLLMChatModel.html) class and passing it to the metric:

```python
from opik.evaluation.metrics import Hallucination
from opik.evaluation.models import LiteLLMChatModel

model = LiteLLMChatModel(
    name="<model_name>",
    base_url="<base_url>"
)

hallucination_metric = Hallucination(
    model=model
)
```

## Creating your own custom model class

You can create your own custom model class by subclassing the [`OpikBaseModel`](https://www.comet.com/docs/opik/python-sdk-reference//Objects/OpikBaseModel.html) class and implementing a few methods:

```python
from opik.evaluation.models import OpikBaseModel
from typing import Any

class CustomModel(OpikBaseModel):
    def __init__(self, model_name: str):
        super().__init__(model_name)

    def generate_provider_response(self, **kwargs: Any) -> str:
        """
        Generate a provider-specific response. Can be used to interface with
        the underlying model provider (e.g., OpenAI, Anthropic) and get raw output.
        """
        pass

    def agenerate_provider_response_stream(self, **kwargs: Any) -> str:
        """
        Generate a provider-specific response. Can be used to interface with
        the underlying model provider (e.g., OpenAI, Anthropic) and get raw output.
        Async version.
        """
        pass

    def generate_string(self, input: str, **kwargs: Any) -> str:
        """Simplified interface to generate a string output from the model."""
        pass

    def agenerate_prompt(self, input: str, **kwargs: Any) -> str:
        """Simplified interface to generate a string output from the model. Async version."""
        return input
```

This model class can then be used in the same way as the built-in models:

```python
from opik.evaluation.metrics import Hallucination

hallucination_metric = Hallucination(
    model=CustomModel()
)
```
