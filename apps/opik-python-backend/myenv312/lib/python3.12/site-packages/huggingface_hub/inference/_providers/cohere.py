from huggingface_hub.inference._providers._common import (
    BaseConversationalTask,
)


_PROVIDER = "cohere"
_BASE_URL = "https://api.cohere.com"


class CohereConversationalTask(BaseConversationalTask):
    def __init__(self):
        super().__init__(provider=_PROVIDER, base_url=_BASE_URL)

    def _prepare_route(self, mapped_model: str, api_key: str) -> str:
        return "/compatibility/v1/chat/completions"
