import pytest
from testix import *
from testix import saveargument

from comet_llm.chains import node, prompt


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(node, "state")
    patch_module(node, "datetimes")
    patch_module(prompt, "convert")


def _construct(
    input_prompt,
    name,
    prompt_template,
    prompt_template_variables,
    metadata,
    id,
):
    with Scenario() as s:
        s.state.get_new_id() >> id
        s.state.get_global_chain() >> Fake("global_chain")
        s.global_chain.track_node(saveargument.SaveArgument("node"))
        s.datetimes.Timer() >> Fake("timer")

        tested = prompt.Prompt(
            prompt=input_prompt,
            name=name,
            prompt_template=prompt_template,
            prompt_template_variables=prompt_template_variables,
            metadata=metadata,
        )

    return tested


def test_as_dict__prompt_template_data_is_used():
    tested = _construct(
        input_prompt="input-prompt",
        name="the-name",
        prompt_template="prompt-template",
        prompt_template_variables="prompt-template-variables",
        metadata={"input-metadata-key": "value-1"},
        id="the-id",
    )

    NOT_DEFINED_FOR_THIS_TEST = None

    with Scenario() as s:
        _prepare_fake_timer("start-timestamp", NOT_DEFINED_FOR_THIS_TEST, NOT_DEFINED_FOR_THIS_TEST)
        s.convert.call_data_to_dict(
            prompt="input-prompt",
            outputs=NOT_DEFINED_FOR_THIS_TEST,
            id="the-id",
            name="the-name",
            metadata={"input-metadata-key": "value-1"},
            prompt_template = "prompt-template",
            prompt_template_variables = "prompt-template-variables",
            category="llm_call",
            start_timestamp = "start-timestamp",
            end_timestamp = NOT_DEFINED_FOR_THIS_TEST,
            duration = NOT_DEFINED_FOR_THIS_TEST,
        ) >> "prompt-data-as-dict"

        assert tested.as_dict() == "prompt-data-as-dict"


def _prepare_fake_timer(start_timestamp, end_timestamp, duration):
    timer = Fake("timer")
    timer.start_timestamp = start_timestamp
    timer.end_timestamp = end_timestamp
    timer.duration = duration
