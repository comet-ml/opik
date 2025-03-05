import pydantic
from typing import Union, Optional
from . import openai_usage

ProviderUsage = Union[
    openai_usage.OpenAIUsage,
    google_usage.GoogleUsage,
]

class OpikUsage(pydantic.BaseModel):
    completion_tokens: int
    prompt_tokens: int

    provider_usage: Union[
        openai_usage.
    ]

    def from_openai_dict()