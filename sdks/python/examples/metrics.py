from opik.evaluation import metrics

# Hallucination metric example
if True:
    print("\n\nHallucination metric example:")

    hallucination_metric = metrics.Hallucination()

    hallucination_score = hallucination_metric.score(
        input="What is the capital of France?",
        output="The capital of France is Paris. It is famous for its iconic Eiffel Tower and rich cultural heritage.",
    )
    print("hallucination_score:", hallucination_score)

# G-Eval metric example
if True:
    print("\n\nG-Eval metric example:")

    g_eval_metric = metrics.GEval(
        task_introduction="You are an expert judge tasked with evaluating the faithfulness of an AI-generated answer to the given context.",
        evaluation_criteria="The OUTPUT must not introduce new information beyond what's provided in the CONTEXT.",
        # model="ollama/llama3"
    )

    g_eval_score = g_eval_metric.score(
        output=str(
            {
                "OUTPUT": "What is the capital of France?",
                "CONTEXT": [
                    "France is a country in Western Europe. Its capital is Paris, which is known for landmarks like the Eiffel Tower."
                ],
            }
        )
    )
    print("g_eval_score:", g_eval_score)

# Moderation metric example
if True:
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
if True:
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
if True:
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
if True:
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


# Structured Output Compliance metric example
if True:
    print("\n\nStructured Output Compliance metric example:")

    structured_output_metric = metrics.StructuredOutputCompliance()

    structured_output_score = structured_output_metric.score(
        output='{"name": "Alice", "age": 30}',
        schema='{"type": "object", "properties": {"name": {"type": "string"}, "age": {"type": "integer"}}, "required": ["name", "age"]}',
    )

    print("structured_output_score:", structured_output_score)

# TrajectoryAccuracy metric example
if True:
    print("\n\nTrajectoryAccuracy metric example:")

    trajectory_accuracy_metric = metrics.TrajectoryAccuracy()

    # Example 1: High-quality ReAct-style agent trajectory
    print("Example 1: High-quality trajectory")
    trajectory_accuracy_score = trajectory_accuracy_metric.score(
        goal="Find the weather in Paris",
        trajectory=[
            {
                "thought": "I need to search for weather information in Paris",
                "action": "search_weather(location='Paris')",
                "observation": "Found weather data for Paris: 22°C, sunny",
            },
            {
                "thought": "I have the weather data, now I should summarize it",
                "action": "summarize_result()",
                "observation": "Summary created: The weather in Paris is 22°C and sunny",
            },
        ],
        final_result="The weather in Paris is 22°C and sunny",
    )
    print("trajectory_accuracy_score:", trajectory_accuracy_score)

    # Example 2: Poor-quality trajectory with wrong actions
    print("\nExample 2: Poor-quality trajectory")
    poor_trajectory_score = trajectory_accuracy_metric.score(
        goal="Find the capital of France",
        trajectory=[
            {
                "thought": "I need to find France's capital",
                "action": "search('weather in France')",  # Wrong action
                "observation": "Found weather information for various French cities",
            },
            {
                "thought": "This doesn't help, let me try something else",
                "action": "search('French cuisine')",  # Still wrong
                "observation": "Found information about French food",
            },
        ],
        final_result="Paris is the capital of France",  # Result doesn't match trajectory
    )
    print("poor_trajectory_score:", poor_trajectory_score)
