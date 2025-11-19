from opik.evaluation.models.langchain import langchain_chat_model
import langchain_openai
import langchain_core.messages
import json
import pydantic
import opik
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    TraceModel,
    assert_equal,
)


def test__langchain_chat_model__happyflow():
    tested = langchain_chat_model.LangchainChatModel(
        chat_model=langchain_openai.ChatOpenAI(
            model_name="gpt-4o",
        ),
        track=False,
    )

    assert isinstance(tested.generate_string("Say hi"), str)
    provider_response = tested.generate_provider_response(
        messages=[
            {
                "content": "Hello, world!",
                "role": "user",
            }
        ]
    )
    assert isinstance(provider_response, langchain_core.messages.AIMessage)
    assert isinstance(provider_response.content, str)


def test__langchain_chat_model__response_format_is_used():
    tested = langchain_chat_model.LangchainChatModel(
        chat_model=langchain_openai.ChatOpenAI(
            model_name="gpt-4o",
        ),
        track=False,
    )

    class Answer(pydantic.BaseModel):
        content: str
        value: str

    response_string = tested.generate_string("What's 2+2?", response_format=Answer)
    structured_response = json.loads(response_string)
    assert isinstance(structured_response, dict)
    assert isinstance(structured_response["content"], str)
    assert isinstance(structured_response["value"], str)


def test__langchain_chat_model__track_enabled__span_and_trace_created_by_OpikTracer_under_the_hood(
    fake_backend,
):
    tested = langchain_chat_model.LangchainChatModel(
        chat_model=langchain_openai.ChatOpenAI(
            model_name="gpt-4o",
        ),
        track=True,
    )

    generated_string = tested.generate_string("Say hi")
    assert isinstance(generated_string, str)
    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="ChatOpenAI",
        input=ANY_BUT_NONE,
        output=ANY_BUT_NONE,
        metadata=ANY_DICT.containing({"created_from": "langchain"}),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
