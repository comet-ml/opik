metrics
=======

Opik includes a number of pre-built metrics to help you evaluate your LLM application.

Each metric can be called as a standalone function using the `score` method::

   from opik.evaluation.metrics import Hallucination

   metric = Hallucination()

   metric.score(
      input="What is the capital of France?",
      output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage.",
      context=["France is a country in Western Europe. Its capital is Paris, which is known for landmarks like the Eiffel Tower."],
   )

Or as part of an evaluation run using the `evaluate` function.

You can learn more about each metric in the following sections:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   Equals
   RegexMatch
   Contains
   IsJson
   LevenshteinRatio

   Hallucination
   GEval
   Moderation

   AnswerRelevance
   ContextPrecision
   ContextRecall
   
   BaseMetric
   ConversationThreadMetric
