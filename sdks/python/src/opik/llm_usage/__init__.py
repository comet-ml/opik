import dataclasses

@dataclasses.dataclass
class OpenAIUsage:
    completion_tokens: int
    """The number of tokens used for the completion."""

    prompt_tokens: int
    """The number of tokens used for the prompt."""

    total_tokens: int
    """The total number of tokens used, including both prompt and completion."""


class 