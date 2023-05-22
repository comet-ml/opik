import pytest
from testix import *
from testix import saveargument

from comet_llm.chains import node


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(node, "state")
    patch_module(node, "datetimes")
    patch_module(node, "convert")


def _construct(
        start_timestamp,
        input,
        category,
        name,
        input_metadata,
    ):
    with Scenario() as s:
        s.datetimes.local_timestamp() >> start_timestamp
        s.state.get_global_chain() >> Fake("global_chain")
        s.global_chain.track_node(saveargument.SaveArgument("node"))

        tested = node.ChainNode(
            input=input,
            category=category,
            name=name,
            input_metadata=input_metadata,
        )

        assert saveargument.saved()["node"] is tested

    return tested

def _use_context_manager(tested, end_timestamp):
    with Scenario() as s:
        s.datetimes.local_timestamp() >> end_timestamp

        with tested:
            pass

    return tested

def test_lifecycle__happyflow():
    START_TIMESTAMP = 10
    END_TIMESTAMP = 15
    DURATION = 5

    tested = _construct(
        start_timestamp=START_TIMESTAMP,
        input="the-input",
        name="the-name",
        category="the-category",
        input_metadata={"input-metadata-key": "value-1"},
    )
    tested = _use_context_manager(tested, end_timestamp=END_TIMESTAMP)
    tested.set_outputs(
        outputs="the-outputs",
        output_metadata={"output-metadata-key": "value-2"}
    )

    with Scenario() as s:
        s.convert.call_data_to_dict(
            prompt="the-input",
            outputs="the-outputs",
            id="the-id",
            metadata={"input-metadata-key": "value-1", "output-metadata-key": "value-2"},
            prompt_template = None,
            prompt_template_variables = None,
            start_timestamp = START_TIMESTAMP,
            end_timestamp = END_TIMESTAMP,
            duration = DURATION,
        ) >> "node-data-as-dict"

        assert tested.as_dict() == "node-data-as-dict"



