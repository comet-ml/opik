from opik.evaluation import metrics

# Hallucination metric example
print("\n\nHallucination metric example:")

hallucination_metric = metrics.Hallucination()

hallucination_score = hallucination_metric.score(
    input="What is the capital of France?",
    output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage.",
)
print("hallucination_score:", hallucination_score)

# G-Eval metric example
print("\n\nG-Eval metric example:")

g_eval_metric = metrics.GEval(
    task_introduction="You are an expert judge tasked with evaluating the faithfulness of an AI-generated answer to the given context.",
    evaluation_criteria="The OUTPUT must not introduce new information beyond what's provided in the CONTEXT.",
)

g_eval_score = g_eval_metric.score(
    input={
        "OUTPUT": "What is the capital of France?",
        "CONTEXT": [
            "France is a country in Western Europe. Its capital is Paris, which is known for landmarks like the Eiffel Tower."
        ],
    }
)
print("g_eval_score:", g_eval_score)

# Moderation metric example
print("\n\nModeration metric example:")

moderation_metric = metrics.Moderation()

moderation_score = moderation_metric.score(
    input="What is the capital of France?",
    output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage.",
    context=[
        "France is a country in Western Europe. Its capital is Paris, which is known for landmarks like the Eiffel Tower."
    ],
)

print("moderation_score:", moderation_score)

# Answer Relevance metric example
print("\n\nAnswer Relevance metric example:")

answer_relevance_metric = metrics.AnswerRelevance()
answer_relevance_score = answer_relevance_metric.score(
    input="What is the capital of France?",
    output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage.",
    context=[
        "France is a country in Western Europe. Its capital is Paris, which is known for landmarks like the Eiffel Tower."
    ],
)
print("answer_relevance_score:", answer_relevance_score)

# ContextPrecision metric example
print("\n\nContextPrecision metric example:")

context_precision_metric = metrics.ContextPrecision()
context_precision_score = context_precision_metric.score(
    input="What is the capital of France?",
    output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage.",
    expected_output="Paris",
    context=[
        "France is a country in Western Europe. Its capital is Paris, which is known for landmarks like the Eiffel Tower."
    ],
)
print("context_precision_score:", context_precision_score)

# ContextRecall metric example
print("\n\nContextRecall metric example:")

context_recall_metric = metrics.ContextRecall()
context_recall_score = context_recall_metric.score(
    input="What is the capital of France?",
    output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage.",
    expected_output="Paris",
    context=[
        "France is a country in Western Europe. Its capital is Paris, which is known for landmarks like the Eiffel Tower."
    ],
)
print("context_recall_score:", context_recall_score)
