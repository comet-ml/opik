import time

import pytest
from testix import *

from comet_llm.message_processing import messages, offline_message_processor

NOT_USED = None


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(offline_message_processor, "prompt")
    patch_module(offline_message_processor, "chain")
    patch_module(offline_message_processor, "time")


def test_offline_message_processor__new_filename_created_because_of_time_passed():
    tested = offline_message_processor.OfflineMessageProcessor(
        offline_directory="/some/path",
        file_usage_duration=5,
    )

    message = messages.PromptMessage(
        experiment_info_=NOT_USED,
        prompt_asset_data=NOT_USED,
        duration=NOT_USED,
        metadata=NOT_USED,
        tags=NOT_USED,
    )

    with Scenario() as s:
        s.time.time() >> 0
        s.prompt.send(message, "/some/path/messages_0.jsonl")
        tested.process(message)

        s.time.time() >> 1
        s.prompt.send(message, "/some/path/messages_0.jsonl")
        tested.process(message)

        s.time.time() >> 4
        s.prompt.send(message, "/some/path/messages_0.jsonl")
        tested.process(message)

        s.time.time() >> 7
        s.prompt.send(message, "/some/path/messages_7.jsonl")
        tested.process(message)


def test_offline_message_processor__messages_dispatched_to_correct_senders():
    tested = offline_message_processor.OfflineMessageProcessor(
        offline_directory="/some/path",
        file_usage_duration=5,
    )

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
        s.time.time() >> 0
        s.prompt.send(prompt_message, "/some/path/messages_0.jsonl")
        tested.process(prompt_message)

        s.time.time() >> 0
        s.chain.send(chain_message, "/some/path/messages_0.jsonl")
        tested.process(chain_message)

        s.time.time() >> 0
        tested.process("some-unhandled-message-type")

