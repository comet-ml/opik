<p align="center">
<picture>
    <source alt="cometLLM"  media="(prefers-color-scheme: dark)" srcset="/logo-dark.svg">
    <img alt="cometLLM" src="/logo.svg">

    <a href="https://badge.fury.io/py/comet-llm">
        <img src="https://badge.fury.io/py/comet-llm.png" alt="PyPI version" height="18">
    </a>
    <a rel="nofollow" href="https://opensource.org/licenses/Apache-2.0">
        <img alt="GitHub" src="https://img.shields.io/badge/License-Apache%202.0-blue.svg">
    </a>
    <a href="https://github.com/comet-ml/kangas/wiki" rel="nofollow">
        <img src="https://img.shields.io/badge/Kangas-Docs-blue.svg" alt="cometLLM Documentation">
    </a>
    <a rel="nofollow" href="https://pepy.tech/project/kangas">
        <img style="max-width: 100%;" data-canonical-src="https://pepy.tech/badge/kangas" alt="Downloads"  src="https://camo.githubusercontent.com/708e470ec83922035f2189544eb968c8c5bba5c8623b0ebb9cb88c5c370766c4/68747470733a2f2f706570792e746563682f62616467652f6b616e676173">
    </a>

</picture>
</p>
<p align="center">
  <b>cometLLM</b> is your new best friend to track your LLM prompts versions and LLM chains, share your learnings, quickly identify what went wrong by visualizing their executions and diffing between them.
</p>
</p>

## ⚡️ Quickstart

Install `comet_llm` Python library with pip:

```bash
pip install comet_llm
```

If you don't have already, [create your free Comet account](https://www.comet.com/signup?utm_source=website&utm_medium=referral&utm_campaign=Online_CV_2023&utm_content=colab-notebook) and grab your API Key from the account settings page. 

Now you are all set to log your first prompt and response:

```python
import comet_llm

comet_llm.log_prompt(
    prompt="What is your name?",
    outputs=" My name is Alex.",
    
)
```

# comet-llm

`comet-llm` is a tool for building LLM(large language models)-based applications that provides an API for logging LLM calls to Comet.
The key features of `comet-llm` include:

Some of the benefits you get by using `comet-llm`.

- **Storage**. Store call inputs, outputs, timings and any other metadata you want in a one place.
- **Visualization**. Observe stored data using new Comet project web UI for LLM.
- **Easy access**. Easily navigate in your project workspace to access any of the stored data. Text search is supported!

## Getting Started

`comet_llm` is accessible as a Python library via pip

```
pip install comet_llm
```

Since that moment you are ready to start using `log_prompt`!

`comet_llm.log_prompt` allows you to manually log the information from a single LLM call.

The minimum required arguments are `prompt` and `outputs`:

```python
import comet_llm

comet_llm.log_prompt(
    prompt="What is your name?",
    outputs=" My name is Alex.",
)
```

But you can also specify additional parameters like that (check the documentation for details).

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
    }
    outputs=" My name is Alex.",
    duration=16.598,
)
```

## Comet credentials

In order to start logging you can configure your environment by providing the following variables:

```
COMET_API_KEY
COMET_WORKSPACE
COMET_PROJECT_NAME
```

As an alternative, you can just pass your Comet API key, workspace name and project name directly to `log_prompt` function.
