# comet_llm

comet_llm provides an API for logging usage of large language models (LLM) to comet.

## Getting Started

`comet_llm` is accessible as a Python library via pip
```
pip install comet_llm
```

## Examples
`comet_llm.log_prompt` allows you to log the data from a single call to LLM.  

The minimum required arguments are `prompt` and `outputs`:
```python
import comet_llm

comet_llm.log_prompt(
    prompt="What is your name?",
    outputs=" My name is Alex.",
)
```
But you can also specify additional parameters like that (check the documentation for details)

```python
import comet_llm

comet_llm.log_prompt(
    prompt="Answer the question and if the question can't be answered, say \"I don't know\"\n\n---\n\nQuestion: What is your name?\nAnswer:",
    prompt_template="Answer the question and if the question can't be answered, say \"I don't know\"\n\n---\n\nQuestion: {{question}}?\nAnswer:",
	  prompt_template_variables={"question": "What is your name?"},
    outputs=" My name is Alex.",
    duration=16.598,
)
```
