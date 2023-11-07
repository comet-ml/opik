import box
import pytest
from testix import *
from testix import saveargument

from comet_llm.chains import span


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(span, "state")
    patch_module(span, "datetimes")
    patch_module(span, "convert")


def _construct(
        inputs,
        category,
        name,
        metadata,
        id,
    ):
    with Scenario() as s:
        s.state.get_new_id() >> id
        s.datetimes.Timer() >> Fake("timer")

        tested = span.Span(
            inputs=inputs,
            category=category,
            name=name,
            metadata=metadata,
        )

    return tested

def test_construct__name_not_defined__use_value_unnamed():
    NOT_DEFINED = None

    with Scenario() as s:
        s.state.get_new_id() >> NOT_DEFINED

        s.datetimes.Timer() >> box.Box(
            start_timestamp=NOT_DEFINED,
            end_timestamp=NOT_DEFINED,
            duration=NOT_DEFINED
        )

        tested = span.Span(
            inputs=NOT_DEFINED,
            category=NOT_DEFINED,
        )
        assert tested.name == "unnamed"


def test_api_start__happyflow():

    NOT_DEFINED = None
    tested = _construct(
        inputs=NOT_DEFINED,
        name=NOT_DEFINED,
        category="the-category",
        metadata=NOT_DEFINED,
        id="the-id",
    )

    with Scenario() as s:
        s.chain.track_node(tested)
        s.chain.context.current() >> "parent-ids"
        s.chain.generate_node_name("the-category") >> "the-name"
        s.timer.start()
        s.chain.context.add("the-id")

        tested.__api__start__(Fake("chain"))

        tested_data = tested.as_dict()

        assert tested_data["parent_ids"] == "parent-ids"
        assert tested_data["name"] == "the-name"


def _use_context_manager_scenario(
        s,
        tested,
        id,
        parent_ids,
        start_timestamp,
        end_timestamp,
        duration
    ):
    global_chain = Fake("global_chain")

    s.state.get_global_chain() >> global_chain

    s.global_chain.track_node(tested)
    s.global_chain.context.current() >> parent_ids

    s.timer.start()
    s.global_chain.context.add(id)

    with tested:
        s.timer.stop()
        s.global_chain.context.pop()

    timer = Fake("timer")
    timer.duration = duration
    timer.start_timestamp = start_timestamp
    timer.end_timestamp = end_timestamp




def test_as_dict__input_and_output_are_not_dicts__input_and_output_turned_into_dicts():
    NOT_DEFINED = None

    tested = _construct(
        inputs="the-inputs",
        name="some-name",
        category=NOT_DEFINED,
        metadata=NOT_DEFINED,
        id=NOT_DEFINED,
    )
    tested.set_outputs(
        outputs="the-outputs",
        metadata=NOT_DEFINED
    )

    assert tested.as_dict()["inputs"] == {"input": "the-inputs"}
    assert tested.as_dict()["outputs"] == {"output": "the-outputs"}


def test_lifecycle__happyflow():
    START_TIMESTAMP = 10
    END_TIMESTAMP = 15
    DURATION = 5

    tested = _construct(
        inputs={"input-key": "input-value"},
        name="the-name",
        category="the-category",
        metadata={"metadata-key": "value-1"},
        id="the-id",
    )
    tested.set_outputs(
        outputs={"output-key": "output-value"},
    )

    with Scenario() as s:
        _use_context_manager_scenario(
            s, tested, "the-id", "parent-ids", START_TIMESTAMP, END_TIMESTAMP, DURATION
        )
        assert tested.as_dict() == {
            "id": "the-id",
            "category": "the-category",
            "name": "the-name",
            "inputs": {"input-key": "input-value"},
            "outputs": {"output-key": "output-value"},
            "duration": DURATION,
            "start_timestamp": START_TIMESTAMP,
            "end_timestamp": END_TIMESTAMP,
            "parent_ids": "parent-ids",
            "metadata": {"metadata-key": "value-1"},
        }


def test_set_output__new_metadata_is_not_None__existing_metadata_is_merged_with_the_new_one():
    NOT_DEFINED = None

    tested = _construct(
        inputs=NOT_DEFINED,
        name="some-name",
        category=NOT_DEFINED,
        metadata={"existing-key": "existing-value"},
        id=NOT_DEFINED,
    )
    tested.set_outputs(
        outputs=NOT_DEFINED,
        metadata={"new-key": "new-value"}
    )

    assert tested.as_dict()["metadata"] == {
        "existing-key": "existing-value",
        "new-key": "new-value",
    }


def test_span__no_chain_started__wont_connect_to_chain():
    START_TIMESTAMP = 10
    END_TIMESTAMP = 15
    DURATION = 5

    with Scenario() as s:
        s.state.get_new_id() >> "example_id"
        timer = Fake("timer")

        timer.duration = DURATION
        timer.start_timestamp = START_TIMESTAMP
        timer.end_timestamp = END_TIMESTAMP

        s.datetimes.Timer() >> timer

        s.state.get_global_chain() >> None

        with span.Span(
            category="llm-call",
            inputs={"input": "input"},
        ) as tested_span:
            tested_span.set_outputs({"outputs": "outputs"})

    assert tested_span.as_dict() == {
            "id": "example_id",
            "category": "llm-call",
            "name": "unnamed",
            "inputs": {"input": "input"},
            "outputs": {"outputs": "outputs"},
            "duration": DURATION,
            "start_timestamp": START_TIMESTAMP,
            "end_timestamp": END_TIMESTAMP,
            "parent_ids": None,
            "metadata": {},
        }