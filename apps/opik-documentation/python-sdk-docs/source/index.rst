Opik
====

=============
Main features
=============

The Comet Opik platform is a suite of tools that allow you to evaluate the output of an LLM powered application.

In includes the following features:

- `Tracing <https://www.comet.com/docs/opik/tracing/log_traces>`_: Ability to log LLM calls and traces to the Opik platform.
- `LLM evaluation metrics <https://www.comet.com/docs/opik/evaluation/metrics/heuristic_metrics>`_: A set of functions that evaluate the output of an LLM, these are both heuristic metrics and LLM as a Judge.
- `Evaluation <https://www.comet.com/docs/opik//evaluation/evaluate_your_llm>`_: Ability to log test datasets in Opik and evaluate using some of our LLM evaluation metrics.

For a more detailed overview of the platform, you can refer to the `Comet Opik documentation <https://www.comet.com/docs/opik>`_.

============
Installation
============

To get start with the package, you can install it using pip::

   pip install opik

To finish configuring the Opik Python SDK, you will need to set the environment variables:

- If you are using the Comet managed Opik platform:

  - `OPIK_API_KEY`: The API key to the Opik platform.
  - `OPIK_WORKSPACE`: The workspace to log traces to, this is often the same as your Opik username.
- If you are using a self-hosted Opik platform:

  - `OPIK_BASE_URL`: The base URL of the Opik platform.

=============
Using the SDK
=============

-----------------
Logging LLM calls
-----------------

To log your first trace, you can use the `track` decorator::

   from opik import track

   @track
   def llm_function(input: str) -> str:
      # Your LLM call
      # ...

      return "Hello, world!"

   llm_function("Hello")

**Note:** The `track` decorator supports nested functions, if you track multiple functions, each functionc call will be associated with the parent trace.

**Integrations**: If you are using LangChain or OpenAI, Comet Opik as `built-in integrations <https://www.comet.com/docs/opik/tracing/integrations/langchain>`_ for these libraries.

----------------------------
Using LLM evaluation metrics
----------------------------

The opik package includes a number of LLM evaluation metrics, these are both heuristic metrics and LLM as a Judge.

All available metrics are listed in the `metrics section <evaluation/metrics/index.html>`_.

These evaluation metrics can be used as::

   from opik.evaluation.metrics import Hallucination

   metric = Hallucination()

   input = "What is the capital of France?"
   output = "The capital of France is Paris, a city known for its iconic Eiffel Tower."
   context = "Paris is the capital and most populous city of France."

   score = metric.score(input, output, context)
   print(f"Hallucination score: {score}")

-------------------
Running evaluations
-------------------

Evaluations are run using the `evaluate` function, this function takes a dataset, a task and a list of metrics and returns a dictionary of scores::

   from opik import Opik, track
   from opik.evaluation import evaluate
   from opik.evaluation.metrics import EqualsMetric, HallucinationMetric
   from opik.integrations.openai import track_openai

   # Define the task to evaluate
   openai_client = track_openai(openai.OpenAI())

   @track()
   def your_llm_application(input: str) -> str:
      response = openai_client.chat.completions.create(
         model="gpt-3.5-turbo",
         messages=[{"role": "user", "content": input}],
      )

      return response.choices[0].message.content

   @track()
   def your_context_retriever(input: str) -> str:
      return ["..."]

   # Fetch the dataset
   client = Opik()
   dataset = client.get_dataset(name="your-dataset-name")

   # Define the metrics
   equals_metric = EqualsMetric()
   hallucination_metric = HallucinationMetric()

   # Define and run the evaluation
   def evaluation_task(x: datasetItem):
      return {
         "input": x.input['user_question'],
         "output": your_llm_application(x.input['user_question']),
         "context": your_context_retriever(x.input['user_question'])
      }

   evaluation = evaluate(
      dataset=dataset,
      task=evaluation_task,
      metrics=[equals_metric, hallucination_metric],
   )

=========
Reference
=========

You can learn more about the `opik` python SDK in the following sections:

.. toctree::
   :maxdepth: 1
   
   Opik
   track
   opik_context/index

.. toctree::
   :caption: Evaluation
   :maxdepth: 1
   
   evaluation/Dataset
   evaluation/DatasetItem
   evaluation/evaluate
   evaluation/metrics/index

.. toctree::
   :caption: Testing
   :maxdepth: 1
   
   testing/llm_unit

.. toctree::
   :caption: Integrations
   :maxdepth: 1
   
   integrations/openai/index
   integrations/langchain/index
   integrations/llama_index/index

.. toctree::
   :caption: Objects
   :maxdepth: 1
   
   Objects/Trace.rst
   Objects/TraceData.rst
   Objects/Span.rst
   Objects/SpanData.rst
   Objects/FeedbackScoreDict.rst
   Objects/UsageDict.rst
   

.. toctree::
   :caption: Documentation Guides
   :maxdepth: 1
   
   Opik Documentation <https://www.comet.com/docs/opik/>
