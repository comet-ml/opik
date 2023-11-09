import box
import pytest
from testix import *

from comet_llm.autologgers.openai import chat_completion_parsers


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(chat_completion_parsers, "metadata")

def test_parse_create_result__input_is_openai_object__input_parsed_successfully():
    create_result = Fake("create_result")
    with Scenario() as s:
        s.metadata.openai_version() >> "0.99.99"
        s.create_result.to_dict() >> {
            "choices": "the-choices",
            "some-key": "some-value",
        }

        outputs, metadata = chat_completion_parsers.parse_create_result(create_result)

        assert outputs == {"choices": "the-choices"}
        assert metadata == {"some-key": "some-value"}


def test_parse_create_result__input_is_openai_object__input_parsed_successfully__model_key_renamed_to_output_model():
    create_result = Fake("create_result")
    with Scenario() as s:
        s.metadata.openai_version() >> "0.99.99"
        s.create_result.to_dict() >> {
            "choices": "the-choices",
            "some-key": "some-value",
            "model": "the-model",
        }

        outputs, metadata = chat_completion_parsers.parse_create_result(create_result)

        assert outputs == {"choices": "the-choices"}
        assert metadata == {"some-key": "some-value", "output_model": "the-model"}


def test_parse_create_result__input_is_generator_object__input_parsed_with_hardcoded_values_used():
    create_result = (x for x in [])

    with Scenario() as s:
        s.metadata.openai_version() >> "0.99.99"
        outputs, metadata = chat_completion_parsers.parse_create_result(create_result)

        assert outputs == {"choices": "Generation is not logged when using stream mode"}
        assert metadata == {}
