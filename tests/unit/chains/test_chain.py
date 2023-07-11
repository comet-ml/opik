import pytest
from testix import *

from comet_llm.chains import chain, version


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(chain, "datetimes")
    patch_module(chain, "context")

def _construct(inputs, metadata):
    with Scenario() as s:
        s.context.Context() >> Fake("context")
        s.datetimes.Timer() >> Fake("timer")
        s.timer.start()
        tested = chain.Chain(
            inputs=inputs,
            metadata=metadata,
            experiment_info="experiment-info",
            tags="the-tags"
        )

    assert tested.experiment_info == "experiment-info"
    assert tested.tags == "the-tags"

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

    tested = _construct({"input-key": "input-value"}, {"meta-input-key": "meta-input-value"})
    tested = _set_outputs(tested, {"output-key": "output-value"}, {"meta-output-key": "meta-output-value"})

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
            "version": version.ASSET_FORMAT_VERSION,
            "chain_nodes": [
                {"node-keys-1": "node-values-1"},
                {"node-keys-2": "node-values-2"},
                {"node-keys-3": "node-values-3"}
            ],
            "category": "chain",
            "chain_inputs": {"input-key": "input-value"},
            "chain_outputs": {"output-key": "output-value"},
            "metadata": {"meta-input-key": "meta-input-value", "meta-output-key": "meta-output-value"},
            "start_timestamp": START_TIMESTAMP,
            "end_timestamp": END_TIMESTAMP,
            "chain_duration": DURATION
        }


def test_as_dict__no_nodes_in_chain__chain_nodes_and_chain_edges_are_empty():
    START_TIMESTAMP = 10
    END_TIMESTAMP = 25
    DURATION = 15

    tested = _construct({"input-key": "input-value"}, {"meta-input-key": "meta-input-value"})
    tested = _set_outputs(tested, {"output-key": "output-value"}, {"meta-output-key": "meta-output-value"})

    with Scenario() as s:
        _prepare_fake_timer(START_TIMESTAMP, END_TIMESTAMP, DURATION)
        assert tested.as_dict() == {
            "version": version.ASSET_FORMAT_VERSION,
            "chain_nodes": [],
            "chain_inputs": {"input-key": "input-value"},
            "chain_outputs": {"output-key": "output-value"},
            "metadata": {"meta-input-key": "meta-input-value", "meta-output-key": "meta-output-value"},
            "category": "chain",
            "start_timestamp": START_TIMESTAMP,
            "end_timestamp": END_TIMESTAMP,
            "chain_duration": DURATION
        }


def test_as_dict__one_node_in_chain__chain_egdes_are_empty():
    START_TIMESTAMP = 10
    END_TIMESTAMP = 25
    DURATION = 15

    tested = _construct({"input-key": "input-value"}, {"meta-input-key": "meta-input-value"})
    tested = _set_outputs(tested, {"output-key": "output-value"}, {"meta-output-key": "meta-output-value"})

    node1 = Fake("node1")

    tested.track_node(node1)

    with Scenario() as s:
        _prepare_fake_timer(START_TIMESTAMP, END_TIMESTAMP, DURATION)
        s.node1.as_dict() >> { "node-keys-1": "node-values-1"}

        assert tested.as_dict() == {
            "version": version.ASSET_FORMAT_VERSION,
            "chain_nodes": [{"node-keys-1": "node-values-1"}],
            "chain_inputs": {"input-key": "input-value"},
            "chain_outputs": {"output-key": "output-value"},
            "metadata": {"meta-input-key": "meta-input-value", "meta-output-key": "meta-output-value"},
            "category": "chain",
            "start_timestamp": START_TIMESTAMP,
            "end_timestamp": END_TIMESTAMP,
            "chain_duration": DURATION
        }


def test_as_dict__input_and_output_are_not_dicts__input_and_output_turned_into_dicts():
    NOT_DEFINED = None

    tested = _construct("the-inputs", NOT_DEFINED)
    tested = _set_outputs(
        tested,
        outputs="the-outputs",
        metadata=NOT_DEFINED
    )
    with Scenario():
        _prepare_fake_timer(NOT_DEFINED, NOT_DEFINED, NOT_DEFINED)
        result = tested.as_dict()

    assert result["chain_inputs"] == {"input": "the-inputs"}
    assert result["chain_outputs"] == {"output": "the-outputs"}


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
