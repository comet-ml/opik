<picture>
<img alt="CometLLM" src="/logo.svg">
</picture>

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
