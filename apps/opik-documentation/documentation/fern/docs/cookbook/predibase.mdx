# Using Opik with Predibase

This notebook demonstrates how to use Predibase as an LLM provider with LangChain, and how to integrate Opik for tracking and logging.

## Setup

First, let's install the necessary packages and set up our environment variables.


```python
%pip install --upgrade --quiet predibase  opik
```

We will now configure Opik and Predibase:


```python
# Configure Opik
import opik
import os
import getpass

opik.configure(use_local=False)

# Configure predibase
os.environ["PREDIBASE_API_TOKEN"] = getpass.getpass("Enter your Predibase API token")
```

## Creating the Opik Tracer

In order to log traces to Opik, we will be using the OpikTracer from the LangChain integration.


```python
# Import Opik tracer
from opik.integrations.langchain import OpikTracer

# Initialize Opik tracer
opik_tracer = OpikTracer(
    tags=["predibase", "langchain"],
)
```

## Initial Call

Let's set up our Predibase model and make an initial call.


```python
from langchain_community.llms import Predibase
import os

model = Predibase(
    model="mistral-7b",
    predibase_api_key=os.environ.get("PREDIBASE_API_TOKEN"),
)

# Test the model with Opik tracing
response = model.invoke(
    "Can you recommend me a nice dry wine?",
    config={"temperature": 0.5, "max_new_tokens": 1024, "callbacks": [opik_tracer]},
)
print(response)
```

In addition to passing the OpikTracer to the invoke method, you can also define it during the creation of the `Predibase` object:

```python
model = Predibase(
    model="mistral-7b",
    predibase_api_key=os.environ.get("PREDIBASE_API_TOKEN"),
).with_config({"callbacks": [opik_tracer]})
```

## SequentialChain

Now, let's create a more complex chain and run it with Opik tracing.


```python
from langchain.chains import LLMChain, SimpleSequentialChain
from langchain_core.prompts import PromptTemplate

# Synopsis chain
template = """You are a playwright. Given the title of play, it is your job to write a synopsis for that title.

Title: {title}
Playwright: This is a synopsis for the above play:"""
prompt_template = PromptTemplate(input_variables=["title"], template=template)
synopsis_chain = LLMChain(llm=model, prompt=prompt_template)

# Review chain
template = """You are a play critic from the New York Times. Given the synopsis of play, it is your job to write a review for that play.

Play Synopsis:
{synopsis}
Review from a New York Times play critic of the above play:"""
prompt_template = PromptTemplate(input_variables=["synopsis"], template=template)
review_chain = LLMChain(llm=model, prompt=prompt_template)

# Overall chain
overall_chain = SimpleSequentialChain(
    chains=[synopsis_chain, review_chain], verbose=True
)

# Run the chain with Opik tracing
review = overall_chain.run("Tragedy at sunset on the beach", callbacks=[opik_tracer])
print(review)
```

## Accessing Logged Traces

We can access the trace IDs collected by the Opik tracer.


```python
traces = opik_tracer.created_traces()
print("Collected trace IDs:", [trace.id for trace in traces])

# Flush traces to ensure all data is logged
opik_tracer.flush()
```

## Fine-tuned LLM Example

Finally, let's use a fine-tuned model with Opik tracing.

**Note:** In order to use a fine-tuned model, you will need to have access to the model and the correct model ID. The code below will return a `NotFoundError` unless the `model` and `adapter_id` are updated.


```python
fine_tuned_model = Predibase(
    model="my-base-LLM",
    predibase_api_key=os.environ.get("PREDIBASE_API_TOKEN"),
    predibase_sdk_version=None,
    adapter_id="my-finetuned-adapter-id",
    adapter_version=1,
    **{
        "api_token": os.environ.get("HUGGING_FACE_HUB_TOKEN"),
        "max_new_tokens": 5,
    },
)

# Configure the Opik tracer
fine_tuned_model = fine_tuned_model.with_config({"callbacks": [opik_tracer]})

# Invode the fine-tuned model
response = fine_tuned_model.invoke(
    "Can you help categorize the following emails into positive, negative, and neutral?",
    **{"temperature": 0.5, "max_new_tokens": 1024},
)
print(response)

# Final flush to ensure all traces are logged
opik_tracer.flush()
```
