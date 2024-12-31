---
sidebar_label: Pytest Integration
description: Describes how to use Opik with Pytest to write LLM unit tests
---

# Pytest Integration

Ensuring your LLM applications is working as expected is a crucial step before deploying to production. Opik provides a Pytest integration so that you can easily track the overall pass / fail rates of your tests as well as the individual pass / fail rates of each test.

## Using the Pytest Integration

We recommend using the `llm_unit` decorator to wrap your tests. This will ensure that Opik can track the results of your tests and provide you with a detailed report. It also works well when used in conjunction with the `track` decorator used to trace your LLM application.

```python
import pytest
from opik import track, llm_unit

@track
def llm_application(user_question: str) -> str:
    # LLM application code here
    return "Paris"

@llm_unit()
def test_simple_passing_test():
    user_question = "What is the capital of France?"
    response = llm_application(user_question)
    assert response == "Paris"
```

When you run the tests, Opik will create a new experiment for each run and log each test result. My navigating to the `tests` dataset, you will see a new experiment for each test run.

![Test Experiments](/img/testing/test_experiments.png)

:::tip
If you are evaluating your LLM application during development, we recommend using the `evaluate` function as it will provide you with a more detailed report. You can learn more about the `evaluate` function in the [evaluation documentation](/evaluation/evaluate_your_llm.md).
:::

### Advanced Usage

The `llm_unit` decorator also works well when used in conjunctions with the `parametrize` Pytest decorator that allows you to run the same test with different inputs:

```python
import pytest
from opik import track, llm_unit

@track
def llm_application(user_question: str) -> str:
    # LLM application code here
    return "Paris"

@llm_unit(expected_output_key="expected_output")
@pytest.mark.parametrize("user_question, expected_output", [
    ("What is the capital of France?", "Paris"),
    ("What is the capital of Germany?", "Berlin")
])
def test_simple_passing_test(user_question, expected_output):
    response = llm_application(user_question)
    assert response == expected_output
```
