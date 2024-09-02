opik
==============

=============
Main features
=============

The Comet Opik platform is a suite of tools that allow you to evaluate the output of an LLM powered application.

In includes the following features:

- `Tracing <...>`_: Ability to log LLM calls and traces to the Comet platform.
- `LLM evaluation metrics <...>`_: A set of functions that evaluate the output of an LLM, these are both heuristic metrics and LLM as a Judge.
- `Evaluation <...>`_: Ability to log test datasets in Comet and evaluate using some of our LLM evaluation metrics.

For a more detailed overview of the platform, you can refer to the `Comet Opik documentation <...>`_.

============
Installation
============

To get start with the package, you can install it using pip::

   pip install opik

By default, all traces, datasets and experiments will be logged to the Comet Cloud platform. If you
would like to self-host the platform, you can refer to our `self-serve documentation <...>`_.

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

**Integrations**: If you are using LangChain or OpenAI, Comet Opik as `built-in integrations <...>`_ for these libraries.

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

   from opik import Comet, track
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
   comet = Comet()
   dataset = comet.get_dataset(name="your-dataset-name")

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



.. toctree::
   :hidden:

   Comet
   track
   comet_context/index

.. toctree::
   :caption: Evaluation
   :hidden:
   :maxdepth: 4
   
   evaluation/Dataset
   evaluation/DatasetItem
   evaluation/evaluate
   evaluation/metrics/index

.. toctree::
   :caption: Integrations
   :hidden:
   :maxdepth: 4
   
   integrations/openai/index
   integrations/langchain/index

.. toctree::
   :caption: Objects
   :hidden:
   :maxdepth: 4
   
   Objects/Trace.rst
   Objects/Span.rst
   Objects/FeedbackScoreDict.rst
   Objects/UsageDict.rst