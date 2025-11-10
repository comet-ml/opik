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
   HeuristicMetrics
   ConversationHeuristicMetrics
   ConversationLLMJudges
   LLMJudgePresets
   LLMJuries
   UtilityMetrics

The pages above fall into two categories:

- Established metric guides (e.g., ``Equals``, ``Hallucination``) that remain the
  authoritative deep dives.
- Aggregation pages that collect the expanded metric families so every class
  exported via :mod:`opik.evaluation.metrics` has an accompanying API reference.

Use these aggregation pages to browse the extended catalog:

- :doc:`HeuristicMetrics` — sentence/word overlap, readability, sentiment, prompt safety, and distribution comparisons.
- :doc:`ConversationHeuristicMetrics` — fast heuristics for degeneracy and knowledge retention.
- :doc:`ConversationLLMJudges` — LLM-as-a-judge conversation evaluators and session quality metrics.
- :doc:`LLMJudgePresets` — pre-built GEval presets and bias checks.
- :doc:`LLMJuries` — multi-judge aggregation.
- :doc:`UtilityMetrics` — helpers such as ``AggregatedMetric`` and ``RagasMetricWrapper``.

Import any metric directly from :mod:`opik.evaluation.metrics`, and pair these API
references with the Fern guides in
``apps/opik-documentation/documentation/fern/docs/evaluation/metrics`` for workflow
context.
