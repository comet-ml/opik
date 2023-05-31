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
    <a href="TODO" rel="nofollow">
        <img src="https://img.shields.io/badge/Kangas-Docs-blue.svg" alt="cometLLM Documentation">
    </a>
    <a rel="nofollow" href="https://pepy.tech/project/comet-llm">
        <img style="max-width: 100%;" data-canonical-src="https://pepy.tech/badge/comet-llm" alt="Downloads"  src="https://camo.githubusercontent.com/708e470ec83922035f2189544eb968c8c5bba5c8623b0ebb9cb88c5c370766c4/68747470733a2f2f706570792e746563682f62616467652f6b616e676173">
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

If you don't have already, [create your free Comet account](https://www.comet.com/signup) and grab your API Key from the account settings page.

Now you are all set to log your first prompt and response:

```python
import comet_llm

comet_llm.log_prompt(
    prompt="What is your name?",
    outputs=" My name is Alex.",
    api_key="<YOUR_COMET_API_KEY>",
)
```

## 🎯 Features

- [x] Log your prompts and responses, including prompt template, variables, timestamps and duration and any metadata that you need.
- [ ] Visualize your prompts and responses in the UI.
- [ ] Log your chain execution down to the level of granularity that you need.
- [ ] Visualize your chain execution in the UI.
- [ ] Diff your prompts and chain execution in the UI.

## 👀 Examples

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
    }
    outputs=" My name is Alex.",
    duration=16.598,
)
```

## ⚙️ Configuration

You can configure your Comet credentials and where you are logging data to Comet:

| Name                 | Python parameter name | Environment variable name |
| -------------------- | --------------------- | ------------------------- |
| Comet API KEY        | api_key               | COMET_API_KEY             |
| Comet Workspace name | workspace             | COMET_WORKSPACE           |
| Comet Project name   | project_name          | COMET_PROJECT_NAME        |
