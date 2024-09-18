---
sidebar_position: 4
sidebar_label: Ragas
---

# Ragas

The Opik SDK provides a simple way to integrate with Ragas, a framework for evaluating RAG systems.

There are two main ways to use Ragas with Opik:

1. Using Ragas to score traces or spans.
2. Using Ragas to evaluate a RAG pipeline.

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/ragas.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Getting started

You will first need to install the `opik` and `ragas` packages:

```bash
pip install opik ragas
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platfrom your API key:

```bash
opik configure
```

## Using Ragas to score traces or spans

Ragas provides a set of metrics that can be used to evaluate the quality of a RAG pipeline, a full list of the supported metrics can be found in the [Ragas documentation](https://docs.ragas.io/en/latest/references/metrics.html#).

In addition to being able to track these feedback scores in Opik, you can also use the `OpikTracer` callback to keep track of the score calculation in Opik.

Due to the asynchronous nature of the score calculation, we will need to define a coroutine to compute the score:

```python
# Import the metric
from ragas.metrics import AnswerRelevancy

# Import some additional dependencies
from langchain_openai.chat_models import ChatOpenAI
from langchain_openai.embeddings import OpenAIEmbeddings
from ragas.llms import LangchainLLMWrapper
from ragas.embeddings import LangchainEmbeddingsWrapper

import asyncio
from ragas.integrations.opik import OpikTracer

# Initialize the Ragas metric
llm = LangchainLLMWrapper(ChatOpenAI())
emb = LangchainEmbeddingsWrapper(OpenAIEmbeddings())
answer_relevancy_metric = AnswerRelevancy(llm=llm, embeddings=emb)

# Define the scoring function
def compute_metric(metric, row):
    row = SingleTurnSample(**row)

    opik_tracer = OpikTracer()

    async def get_score(opik_tracer, metric, row):
        score = await metric.single_turn_ascore(row, callbacks=[OpikTracer()])
        return score

    # Run the async function using the current event loop
    loop = asyncio.get_event_loop()

    result = loop.run_until_complete(get_score(opik_tracer, metric, row))
    return result
```

Once the `compute_metric` function is defined, you can use it to score a trace or span:

```python
from opik import track
from opik.opik_context import update_current_trace


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
    row = {"user_input": question, "response": answer, "retrieved_contexts": contexts}
    score = compute_metric(answer_relevancy_metric, row)
    return score


@track
def rag_pipeline(question):
    # Define the pipeline
    contexts = retrieve_contexts(question)
    answer = answer_question(question, contexts)

    score = compute_rag_score(answer_relevancy_metric, question, answer, contexts)
    update_current_trace(
        feedback_scores=[{"name": "answer_relevancy", "value": round(score, 4)}]
    )

    return answer


rag_pipeline("What is the capital of France?")
```

In the Opik UI, you will be able to see the full trace including the score calculation:

![Ragas chain](/img/tracing/ragas_opik_trace.png)

## Using Ragas to evaluate a RAG pipeline

:::tip

We recommend using the Opik [evaluation framework](/evaluation/evaluate_your_llm) to evaluate your RAG pipeline. It shares similar concepts with the Ragas `evaluate` functionality but has a tighter integration with Opik.

:::

If you are using the Ragas `evaluate` functionality, you can use the `OpikTracer` callback to keep track of the score calculation in Opik. This will track as traces the computation of each evaluation metric:

```python
from datasets import load_dataset
from ragas.metrics import context_precision, answer_relevancy, faithfulness
from ragas import evaluate
from ragas.integrations.opik import OpikTracer

fiqa_eval = load_dataset("explodinggradients/fiqa", "ragas_eval")

# Reformat the dataset to match the schema expected by the Ragas evaluate function
dataset = fiqa_eval["baseline"].select(range(3))

dataset = dataset.map(
    lambda x: {
        "user_input": x["question"],
        "reference": x["ground_truths"][0],
        "retrieved_contexts": x["contexts"],
    }
)

opik_tracer_eval = OpikTracer(tags=["ragas_eval"], metadata={"evaluation_run": True})

result = evaluate(
    dataset,
    metrics=[context_precision, faithfulness, answer_relevancy],
    callbacks=[opik_tracer_eval],
)

print(result)
```
