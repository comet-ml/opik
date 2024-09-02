# Using Ragas to evaluate RAG pipelines

In this notebook, we will showcase how to use Opik with Ragas for monitoring and evaluation of RAG (Retrieval-Augmented Generation) pipelines.

There are two main ways to use Opik with Ragas:

1. Using Ragas metrics to score traces
2. Using the Ragas `evaluate` function to score a dataset

## Creating an account on Comet.com

[Comet](https://www.comet.com/site) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm) and grab you API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/self_hosting_opik) for more information.


```python
import os
import getpass

os.environ["OPIK_API_KEY"] = getpass.getpass("Opik API Key: ")
os.environ["OPIK_WORKSPACE"] = input("Comet workspace (often the same as your username): ")
```

If you are running the Opik platform locally, simply set:


```python
# import os
# os.environ["OPIK_URL_OVERRIDE"] = "http://localhost:5173/api"
```

## Preparing our environment

First, we will install the necessary libraries and configure the OpenAI API key.


```python
%pip install opik ragas --quiet

import os
import getpass

os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")
```

## Integrating Opik with Ragas

### Using Ragas metrics to score traces

Ragas provides a set of metrics that can be used to evaluate the quality of a RAG pipeline, including but not limited to: `answer_relevancy`, `answer_similarity`, `answer_correctness`, `context_precision`, `context_recall`, `context_entity_recall`, `summarization_score`. You can find a full list of metrics in the [Ragas documentation](https://docs.ragas.io/en/latest/references/metrics.html#).

These metrics can be computed on the fly and logged to traces or spans in Opik. For this example, we will start by creating a simple RAG pipeline and then scoring it using the `answer_relevancy` metric.

#### Create the Ragas metric

In order to use the Ragas metric without using the `evaluate` function, you need to initialize the metric with a `RunConfig` object and an LLM provider. For this example, we will use LangChain as the LLM provider with the Opik tracer enabled.

We will first start by initializing the Ragas metric:


```python
# Import the metric
from ragas.metrics import AnswerRelevancy

# Import some additional dependencies
from langchain_openai.chat_models import ChatOpenAI
from langchain_openai.embeddings import OpenAIEmbeddings
from ragas.llms import LangchainLLMWrapper
from ragas.embeddings import LangchainEmbeddingsWrapper

# Initialize the Ragas metric
llm = LangchainLLMWrapper(ChatOpenAI())
emb = LangchainEmbeddingsWrapper(OpenAIEmbeddings())

answer_relevancy_metric = AnswerRelevancy(llm=llm, embeddings=emb)
```

Once the metric is initialized, you can use it to score a sample question. Given that the metric scoring is done asynchronously, you need to use the `asyncio` library to run the scoring function.


```python
# Run this cell first if you are running this in a Jupyter notebook
import nest_asyncio

nest_asyncio.apply()
```


```python
import asyncio
from ragas.integrations.opik import OpikTracer

# Define the scoring function
def compute_metric(opik_tracer, metric, row):
    async def get_score(opik_tracer, metric, row):
        score = await metric.ascore(row, callbacks=[opik_tracer])
        return score

    # Run the async function using the current event loop
    loop = asyncio.get_event_loop()
    
    result = loop.run_until_complete(get_score(opik_tracer, metric, row))
    return result

# Score a simple example
row = {
    "question": "What is the capital of France?",
    "answer": "Paris",
    "contexts": ["Paris is the capital of France.", "Paris is in France."]
}

opik_tracer = OpikTracer()
score = compute_metric(opik_tracer, answer_relevancy_metric, row)
print("Answer Relevancy score:", score)
```

If you now navigate to Opik, you will be able to see that a new trace has been created in the `Default Project` project.

#### Score traces

You can score traces by using the `get_current_trace` function to get the current trace and then calling the `log_feedback_score` function.

The advantage of this approach is that the scoring span is added to the trace allowing for a more fine-grained analysis of the RAG pipeline. It will however run the Ragas metric calculation synchronously and so might not be suitable for production use-cases.


```python
from opik import track
from opik.opik_context import get_current_trace

@track
def retrieve_contexts(question):
    # Define the retrieval function, in this case we will hard code the contexts
    return ["Paris is the capital of France.", "Paris is in France."]

@track
def answer_question(question, contexts):
    # Define the answer function, in this case we will hard code the answer
    return "Paris"

@track(name="Compute Ragas metric score", capture_input=False)
def compute_rag_score(answer_relevancy_metric, question, answer, contexts):
    # Define the score function
    row = {"question": question, "answer": answer, "contexts": contexts}
    score = compute_metric(answer_relevancy_metric, row)
    return score

@track
def rag_pipeline(question):
    # Define the pipeline
    contexts = retrieve_contexts(question)
    answer = answer_question(question, contexts)

    trace = get_current_trace()
    score = compute_rag_score(answer_relevancy_metric, question, answer, contexts)
    trace.log_feedback_score("answer_relevancy", round(score, 4), category_name="ragas")
    
    return answer

rag_pipeline("What is the capital of France?")
```

#### Evaluating datasets

If you looking at evaluating a dataset, you can use the Ragas `evaluate` function. When using this function, the Ragas library will compute the metrics on all the rows of the dataset and return a summary of the results.

You can use the `OpikTracer` callback to log the results of the evaluation to the Opik platform:


```python
from datasets import load_dataset
from ragas.metrics import context_precision, answer_relevancy, faithfulness
from ragas import evaluate
from ragas.integrations.opik import OpikTracer

fiqa_eval = load_dataset("explodinggradients/fiqa", "ragas_eval")

opik_tracer_eval = OpikTracer(tags=["ragas_eval"], metadata={"evaluation_run": True})

result = evaluate(
    fiqa_eval["baseline"].select(range(3)),
    metrics=[context_precision, faithfulness, answer_relevancy],
    callbacks=[opik_tracer_eval]
)

print(result)
```
