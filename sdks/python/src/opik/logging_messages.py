METADATA_KEY_COLLISION_DURING_DEEPMERGE = (
    "Trace or span metadata value for the sub-key '%s' was overwritten from '%s' to '%s' during the deep merge",
)

INVALID_USAGE_WILL_NOT_BE_LOGGED = "Provided usage %s will not be logged because it does not follow expected format.\nReason: %s"

INVALID_FEEDBACK_SCORE_WILL_NOT_BE_LOGGED = "Provided feedback score %s will not be logged because it does not follow expected format.\nReason: %s"

EXCEPTION_RAISED_FROM_TRACKED_FUNCTION = (
    "Exception raised from tracked function %s.\nInputs: %s\nError message: %s"
)

FAILED_TO_EXTRACT_TOKEN_USAGE_FROM_PRESUMABLY_LANGCHAIN_OPENAI_LLM_RUN = (
    "Failed to extract token usage from presumably OpenAI LLM langchain run."
)

UNEXPECTED_EXCEPTION_ON_SPAN_CREATION_FOR_TRACKED_FUNCTION = "Unexpected exception happened when tried to create a span for function %s.\nInputs: %s\nError message: %s"

UNEXPECTED_EXCEPTION_ON_SPAN_FINALIZATION_FOR_TRACKED_FUNCTION = "Unexpected exception happened when tried to finalize span.\nOutput: %s\nError message: %s"

FAILED_TO_AGGREGATE_GENERATORS_YIELDED_VALUES_WITH_PROVIDED_AGGREGATOR_IN_TRACKED_FUNCTION = "Failed to aggregate generators yielded values with provided aggregator. Values: %s, generator %s"

FAILED_TO_PARSE_OPENAI_STREAM_CONTENT = "Failed to parse openai Stream content. %s"

FAILED_TO_PROCESS_MESSAGE_IN_BACKGROUND_STREAMER = (
    "Failed to process %s. Content: %s, Error: %s"
)

HALLUCINATION_DETECTION_FAILED = "Failed hallucination detection"

FACTUALITY_SCORE_CALC_FAILED = "Failed to calculate factuality score"

ANSWER_RELEVANCE_SCORE_CALC_FAILED = "Failed to calculate answer relevance score"

MODERATION_SCORE_CALC_FAILED = "Failed to calculate moderation score"

CONTEXT_RECALL_SCORE_CALC_FAILED = "Failed to calculate context recall score"

GEVAL_SCORE_CALC_FAILED = "Failed to calculate g-eval score"

CONTEXT_PRECISION_SCORE_CALC_FAILED = "Failed to calculate context precision score"

NESTED_SPAN_PROJECT_NAME_MISMATCH_WARNING_MESSAGE = (
    'You are attempting to log data into a nested span under the project name "{}". '
    'However, the project name "{}" from parent span will be used instead.'
)

PARSE_API_KEY_EMPTY_KEY = "Can not parse empty Opik API key"

PARSE_API_KEY_EMPTY_EXPECTED_ATTRIBUTES = (
    "Expected attributes not found in the Opik API key: %r"
)

PARSE_API_KEY_TOO_MANY_PARTS = "Too many parts (%d) found in the Opik API key: %r"
