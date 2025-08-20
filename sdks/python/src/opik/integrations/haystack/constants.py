from typing import List

SUPPORTED_GENERATORS: List[str] = [
    "AzureOpenAIGenerator",
    "OpenAIGenerator",
    "AnthropicGenerator",
    "HuggingFaceAPIGenerator",
    "HuggingFaceLocalGenerator",
    "CohereGenerator",
]

SUPPORTED_CHAT_GENERATORS: List[str] = [
    "AzureOpenAIChatGenerator",
    "OpenAIChatGenerator",
    "AnthropicChatGenerator",
    "HuggingFaceAPIChatGenerator",
    "HuggingFaceLocalChatGenerator",
    "CohereChatGenerator",
]

ALL_SUPPORTED_GENERATORS: List[str] = SUPPORTED_GENERATORS + SUPPORTED_CHAT_GENERATORS

# Haystack component output keys
COMPONENT_OUTPUT_KEY: str = "haystack.component.output"
COMPONENT_TYPE_KEY: str = "haystack.component.type"
COMPONENT_NAME_KEY: str = "haystack.component.name"

# Pipeline data keys
PIPELINE_INPUT_DATA_KEY: str = "haystack.pipeline.input_data"
PIPELINE_OUTPUT_DATA_KEY: str = "haystack.pipeline.output_data"

# Operation keys
PIPELINE_RUN_KEY: str = "haystack.pipeline.run"

HAYSTACK_OPIK_ENFORCE_FLUSH_ENV_VAR = "HAYSTACK_OPIK_ENFORCE_FLUSH"
