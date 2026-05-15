from unittest import mock

import openai

from opik.integrations.openai import track_openai


QIANFAN_BASE_URL = "https://qianfan.baidubce.com/v2"
QIANFAN_PROVIDER = "qianfan.baidubce.com"


def test_track_openai__non_openai_base_url__provider_is_forwarded_to_chat_and_responses_decorators():
    client = openai.OpenAI(
        api_key="test-api-key",
        base_url=QIANFAN_BASE_URL,
    )

    chat_decorator_factory = mock.Mock()
    chat_decorator_factory.track.side_effect = lambda **kwargs: (
        lambda original: original
    )
    responses_decorator_factory = mock.Mock()
    responses_decorator_factory.track.side_effect = lambda **kwargs: (
        lambda original: original
    )

    with mock.patch(
        "opik.integrations.openai.openai_chat_completions_decorator.OpenaiChatCompletionsTrackDecorator",
        return_value=chat_decorator_factory,
    ), mock.patch(
        "opik.integrations.openai.openai_responses_decorator.OpenaiResponsesTrackDecorator",
        return_value=responses_decorator_factory,
    ):
        track_openai(client)

    assert chat_decorator_factory.provider == QIANFAN_PROVIDER
    assert responses_decorator_factory.provider == QIANFAN_PROVIDER
