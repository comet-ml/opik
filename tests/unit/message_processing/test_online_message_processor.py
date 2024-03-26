import pytest
from testix import *

from comet_llm.message_processing import messages, online_message_processor

NOT_USED = None

@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(online_message_processor, "prompt")
    patch_module(online_message_processor, "chain")
    patch_module(online_message_processor, "time")


def test_offline_message_processor__messages_dispatched_to_correct_senders():
    tested = online_message_processor.OnlineMessageProcessor()
    prompt_message = messages.PromptMessage(
        experiment_info_=NOT_USED,
        prompt_asset_data=NOT_USED,
        duration=NOT_USED,
        metadata=NOT_USED,
        tags=NOT_USED,
    )

    chain_message = messages.ChainMessage(
        experiment_info_=NOT_USED,
        chain_data=NOT_USED,
        duration=NOT_USED,
        tags=NOT_USED,
        metadata=NOT_USED,
        others=NOT_USED,
    )
    with Scenario() as s:
        s.prompt.send(prompt_message)
        tested.process(prompt_message)

        s.chain.send(chain_message)
        tested.process(chain_message)

        tested.process("some-unhandled-message-type")

