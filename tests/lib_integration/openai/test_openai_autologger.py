import json
import os

import pytest

import comet_llm
import logging
import comet_llm.chains.state

from ... import testlib

LOGGER = logging.getLogger(__file__)

@pytest.fixture
def comet_setup():
    with testlib.environ({"COMET_API_KEY": "FAKE-KEY"}):
        yield

@pytest.mark.forked
def test_openai_autologger__chain_exists__openai_call_was_made__openai_call_added_to_chain_as_node(comet_setup):
    import openai

    LOGGER.error(f"ENV INFO: {os.environ['OPENAI_API_KEY'][:5]} {os.environ['OPENAI_ORG_ID'][:5]}")

    comet_llm.start_chain(
        inputs={"any-name": "any-input"},
        metadata={"a": 123},
    )
    client = openai.OpenAI()

    response = client.chat.completions.create(
        model="gpt-3.5-turbo",
        messages=[
            {"role": "user", "content": "Tell a fact?"}
        ],
        max_tokens=10
    )

    chain = comet_llm.chains.state.get_global_chain()
    chain_data = chain.as_dict()

    assert len(chain_data["chain_nodes"]) == 1

    node = chain_data["chain_nodes"][0]

    assert node["inputs"] == {"messages":[{"role": "user", "content": "Tell a fact?"}]}

    response_dict = response.model_dump()

    assert node["outputs"] == {"choices": response_dict["choices"]}
    assert "usage" in node["metadata"]

    # check that chain data is JSON-serializable
    json.dumps(chain_data)
