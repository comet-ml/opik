# Using Opik with DSPy

[DSPy](https://dspy.ai/) is the framework for programming—rather than prompting—language models.

In this guide, we will showcase how to integrate Opik with DSPy so that all the DSPy calls are logged as traces in Opik.

## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=dspy&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=dspy&utm_campaign=opik) and grab you API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=dspy&utm_campaign=opik) for more information.


```python
%pip install --upgrade opik dspy
```


```python
import opik

opik.configure(use_local=False)
```


```python
import os
import getpass

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")
```

## Logging traces

In order to log traces to Opik, you will need to set the `opik` callback:


```python
import dspy
from opik.integrations.dspy.callback import OpikCallback

lm = dspy.LM("openai/gpt-4o-mini")

project_name = "DSPY"
opik_callback = OpikCallback(project_name=project_name)

dspy.configure(lm=lm, callbacks=[opik_callback])
```


```python
cot = dspy.ChainOfThought("question -> answer")
cot(question="What is the meaning of life?")
```

The trace is now logged to the Opik platform:

![DSPy trace](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/fern/img/cookbook/dspy_trace_cookbook.png)
