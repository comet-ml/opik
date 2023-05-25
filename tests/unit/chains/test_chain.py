import pytest
from testix import *

from comet_llm.chains import chain


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(chain, "datetimes")


def _construct(inputs, metadata):
    with Scenario() as s:
        s.datetimes.Timer() >> Fake("timer")
        tested = chain.Chain(inputs=inputs, metadata=metadata)

    return tested

def _set_outputs(tested, outputs, metadata):
    with Scenario() as s:
        s.timer.stop()
        tested.set_outputs(outputs, metadata)

    return tested

def test_as_dict__happyflow():
    START_TIMESTAMP = 10
    END_TIMESTAMP = 25
    DURATION = 15

    tested = _construct("the-inputs", {"input-key": "input-value"})
    tested = _set_outputs(tested, "the-outputs", {"output-key": "output-value"})

    tested.outputs = "the-outputs"

    node1 = Fake("node1")
    node2 = Fake("node2")
    node3 = Fake("node3")

    tested.track_node(node1)
    tested.track_node(node2)
    tested.track_node(node3)

    with Scenario() as s:
        s.node1.as_dict() >> {"node-keys-1": "node-values-1"}
        s.node2.as_dict() >> {"node-keys-2": "node-values-2"}
        s.node3.as_dict() >> {"node-keys-3": "node-values-3"}

        node1.id = "id-1"
        node2.id = "id-2"
        node3.id = "id-3"

        _prepare_fake_timer(START_TIMESTAMP, END_TIMESTAMP, DURATION)

        assert tested.as_dict() == {
            "_version": "the-version",
            "chain_nodes": [
                {"node-keys-1": "node-values-1"},
                {"node-keys-2": "node-values-2"},
                {"node-keys-3": "node-values-3"}
            ],
            "chain_edges": [["id-1", "id-2"], ["id-2", "id-3"]],
            "chain_context": {},
            "chain_inputs": "the-inputs",
            "chain_outputs": "the-outputs",
            "metadata": {"input-key": "input-value", "output-key": "output-value"},
            "start_timestamp": START_TIMESTAMP,
            "end_timestamp": END_TIMESTAMP,
            "duration": DURATION
        }


def test_as_dict__no_nodes_in_chain__chain_nodes_and_chain_edges_are_empty():
    START_TIMESTAMP = 10
    END_TIMESTAMP = 25
    DURATION = 15

    tested = _construct("the-inputs", {"input-key": "input-value"})
    tested = _set_outputs(tested, "the-outputs", {"output-key": "output-value"})

    tested.outputs = "the-outputs"

    with Scenario() as s:
        _prepare_fake_timer(START_TIMESTAMP, END_TIMESTAMP, DURATION)
        assert tested.as_dict() == {
            "_version": "the-version",
            "chain_nodes": [],
            "chain_edges": [],
            "chain_context": {},
            "chain_inputs": "the-inputs",
            "chain_outputs": "the-outputs",
            "metadata": {"input-key": "input-value", "output-key": "output-value"},
            "start_timestamp": START_TIMESTAMP,
            "end_timestamp": END_TIMESTAMP,
            "duration": DURATION
        }


def test_as_dict__one_node_in_chain__chain_egdes_are_empty():
    START_TIMESTAMP = 10
    END_TIMESTAMP = 25
    DURATION = 15

    tested = _construct("the-inputs", {"input-key": "input-value"})
    tested = _set_outputs(tested, "the-outputs", {"output-key": "output-value"})

    tested.outputs = "the-outputs"

    node1 = Fake("node1")

    tested.track_node(node1)

    with Scenario() as s:
        _prepare_fake_timer(START_TIMESTAMP, END_TIMESTAMP, DURATION)
        s.node1.as_dict() >> { "node-keys-1": "node-values-1"}

        assert tested.as_dict() == {
            "_version": "the-version",
            "chain_nodes": [{"node-keys-1": "node-values-1"}],
            "chain_edges": [],
            "chain_context": {},
            "chain_inputs": "the-inputs",
            "chain_outputs": "the-outputs",
            "metadata": {"input-key": "input-value", "output-key": "output-value"},
            "start_timestamp": START_TIMESTAMP,
            "end_timestamp": END_TIMESTAMP,
            "duration": DURATION
        }


def _prepare_fake_timer(start_timestamp, end_timestamp, duration):
    timer = Fake("timer")
    timer.start_timestamp = start_timestamp
    timer.end_timestamp = end_timestamp
    timer.duration = duration


def test_generate_node_name__names_are_generated_with_uniqie_category_counter():
    NOT_DEFINED = None

    tested = _construct(NOT_DEFINED, NOT_DEFINED)

    assert tested.generate_node_name(category="tool") == "tool-0"
    assert tested.generate_node_name(category="tool") == "tool-1"
    assert tested.generate_node_name(category="llm-call") == "llm-call-0"
    assert tested.generate_node_name(category="tool") == "tool-2"
