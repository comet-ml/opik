<p align="center">
    <picture>
        <source alt="cometLLM" media="(prefers-color-scheme: dark)" srcset="https://github.com/comet-ml/comet-llm/raw/main/logo-dark.svg">
        <img alt="cometLLM" src="https://github.com/comet-ml/comet-llm/raw/main/logo.svg">
    </picture>
</p>
<p align="center">
    <a href="https://pypi.org/project/comet-llm">
        <img src="https://img.shields.io/pypi/v/comet-llm" alt="PyPI version">
    </a>
    <a rel="nofollow" href="https://opensource.org/license/mit/">
        <img alt="GitHub" src="https://img.shields.io/badge/License-MIT-blue.svg">
    </a>
    <a href="https://www.comet.com/docs/v2/guides/large-language-models/overview/" rel="nofollow">
        <img src="https://img.shields.io/badge/cometLLM-Docs-blue.svg" alt="cometLLM Documentation">
    </a>
    <a rel="nofollow" href="https://pepy.tech/project/comet-llm">
        <img style="max-width: 100%;" src="https://static.pepy.tech/badge/comet-llm" alt="Downloads">
    </a>
    <a rel="nofollow" href="https://colab.research.google.com/github/comet-ml/comet-llm/blob/main/examples/CometLLM_Prompts.ipynb">
        <img src="https://colab.research.google.com/assets/colab-badge.svg">
    </a>
</p>
<p align="center">
    <b>CometLLM</b> is a tool to log and visualize your LLM prompts and chains. Use CometLLM to identify effective prompt strategies, streamline your troubleshooting, and ensure reproducible workflows!
</p>
</p>

![CometLLM Preview](https://github.com/comet-ml/comet-llm/raw/main/comet_llm.gif)

## ⚡️ Quickstart

Install `comet_llm` Python library with pip:

```bash
pip install comet_llm
```

If you don't have already, [create your free Comet account](https://www.comet.com/signup/?utm_source=comet_llm&utm_medium=referral&utm_content=github&framework=llm) and grab your API Key from the account settings page.

Now you are all set to log your first prompt and response:

```python
import comet_llm

comet_llm.log_prompt(
    prompt="What is your name?",
    output=" My name is Alex.",
    api_key="<YOUR_COMET_API_KEY>",
)
```

## 🎯 Features

- [x] Log your prompts and responses, including prompt template, variables, timestamps and duration and any metadata that you need.
- [x] Visualize your prompts and responses in the UI.
- [x] Log your chain execution down to the level of granularity that you need.
- [x] Visualize your chain execution in the UI.
- [ ] Diff your prompts and chain execution in the UI.

## 👀 Examples

To log a single LLM call as an individual prompt, use `comet_llm.log_prompt`. If you require more granularity, you can log a chain of executions that may include more than one LLM call, context retrieval, or data pre- or post-processing with `comet_llm.start_chain`.

### Log a full prompt and response

```python
import comet_llm

comet_llm.log_prompt(
    prompt="Answer the question and if the question can't be answered, say \"I don't know\"\n\n---\n\nQuestion: What is your name?\nAnswer:",
    prompt_template="Answer the question and if the question can't be answered, say \"I don't know\"\n\n---\n\nQuestion: {{question}}?\nAnswer:",
    prompt_template_variables={"question": "What is your name?"},
    metadata= {
        "usage.prompt_tokens": 7,
        "usage.completion_tokens": 5,
        "usage.total_tokens": 12,
    },
    output=" My name is Alex.",
    duration=16.598,
)
```

[Read the full documentation for more details about logging a prompt](https://www.comet.com/docs/v2/guides/large-language-models/llm-project/#logging-prompts-to-llm-projects).

### Log a LLM chain

```python
from comet_llm import Span, end_chain, start_chain
import datetime
from time import sleep


def retrieve_context(user_question):
    if "open" in user_question:
        return "Opening hours: 08:00 to 17:00 all days"


def llm_answering(user_question, current_time, context):
    prompt_template = """You are a helpful chatbot. You have access to the following context:
    {context}
    The current time is: {current_time}
    Analyze the following user question and decide if you can answer it, if the question can't be answered, say \"I don't know\":
    {user_question}
    """

    prompt = prompt_template.format(
        user_question=user_question, current_time=current_time, context=context
    )

    with Span(
        category="llm-call",
        inputs={"prompt_template": prompt_template, "prompt": prompt},
    ) as span:
        # Call your LLM model here
        sleep(0.1)
        result = "Yes we are currently open"
        usage = {"prompt_tokens": 52, "completion_tokens": 12, "total_tokens": 64}

        span.set_outputs(outputs={"result": result}, metadata={"usage": usage})

    return result


def main(user_question, current_time):
    start_chain(inputs={"user_question": user_question, "current_time": current_time})

    with Span(
        category="context-retrieval",
        name="Retrieve Context",
        inputs={"user_question": user_question},
    ) as span:
        context = retrieve_context(user_question)

        span.set_outputs(outputs={"context": context})

    with Span(
        category="llm-reasoning",
        inputs={
            "user_question": user_question,
            "current_time": current_time,
            "context": context,
        },
    ) as span:
        result = llm_answering(user_question, current_time, context)

        span.set_outputs(outputs={"result": result})

    end_chain(outputs={"result": result})


main("Are you open?", str(datetime.datetime.now().time()))
```

[Read the full documentation for more details about logging a chain](https://www.comet.com/docs/v2/guides/large-language-models/llm-project/#logging-chains-to-llm-projects).

## ⚙️ Configuration

You can configure your Comet credentials and where you are logging data to:

| Name                 | Python parameter name | Environment variable name |
| -------------------- | --------------------- | ------------------------- |
| Comet API KEY        | api_key               | COMET_API_KEY             |
| Comet Workspace name | workspace             | COMET_WORKSPACE           |
| Comet Project name   | project               | COMET_PROJECT_NAME        |

## 📝 License

Copyright (c) [Comet](https://www.comet.com/site/) 2023-present. `cometLLM` is free and open-source software licensed under the [MIT License](https://github.com/comet-ml/comet-llm/blob/master/LICENSE).
