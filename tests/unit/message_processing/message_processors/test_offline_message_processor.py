import glob
import json
import os
import shutil

import pytest
from testix import *

from comet_llm import experiment_info
from comet_llm.message_processing import messages
from comet_llm.message_processing.message_processors import offline_message_processor

NOT_USED = None


@pytest.fixture()
def mock_imports(patch_module):
    patch_module(offline_message_processor, "prompt")
    patch_module(offline_message_processor, "chain")
    patch_module(offline_message_processor, "time")
    patch_module(offline_message_processor, "random")
    patch_module(offline_message_processor, "os")


def test_offline_message_processor__new_filename_created_because_of_time_passed(mock_imports):
    message = messages.PromptMessage(
        experiment_info_=NOT_USED,
        prompt_asset_data=NOT_USED,
        duration=NOT_USED,
        metadata=NOT_USED,
        tags=NOT_USED,
    )

    with Scenario() as s:
        s.os.makedirs("/some/path", exist_ok=True)
        tested = offline_message_processor.OfflineMessageProcessor(
            offline_directory="/some/path",
            file_usage_duration=5,
        )

        s.time.time() >> 0
        s.random.randint(1111,9999) >> 1234
        s.prompt.send(message, "/some/path/messages_0_1234.jsonl")
        tested.process(message)

        s.time.time() >> 1
        s.prompt.send(message, "/some/path/messages_0_1234.jsonl")
        tested.process(message)

        s.time.time() >> 4
        s.prompt.send(message, "/some/path/messages_0_1234.jsonl")
        tested.process(message)

        s.time.time() >> 7
        s.random.randint(1111,9999) >> 5678
        s.prompt.send(message, "/some/path/messages_7_5678.jsonl")
        tested.process(message)


def test_offline_message_processor__messages_dispatched_to_correct_senders(mock_imports):
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
        s.os.makedirs("/some/path", exist_ok=True)
        tested = offline_message_processor.OfflineMessageProcessor(
            offline_directory="/some/path",
            file_usage_duration=5,
        )

        s.time.time() >> 0
        s.random.randint(1111,9999) >> 1234
        s.prompt.send(prompt_message, "/some/path/messages_0_1234.jsonl")
        tested.process(prompt_message)

        s.time.time() >> 0
        s.chain.send(chain_message, "/some/path/messages_0_1234.jsonl")
        tested.process(chain_message)

        s.time.time() >> 0
        tested.process("some-unhandled-message-type")


@pytest.fixture
def offline_directory():
    dirpath = "./test-offline-dir"
    os.makedirs(dirpath)
    yield dirpath
    shutil.rmtree(dirpath)


def test_offline_message_processor_with_senders__prompt_and_chain_messages__happyflow(offline_directory):
    tested = offline_message_processor.OfflineMessageProcessor(
        offline_directory=offline_directory,
        file_usage_duration=5,
    )

    experiment_info_ = experiment_info.get(
        workspace="the-workspace",
        project_name="the-project-name",
        api_key="api-key"
    )

    prompt_message = messages.PromptMessage(
        experiment_info_=experiment_info_,
        prompt_asset_data={"prompt-asset-key": "prompt-asset-value"},
        duration="prompt-duration",
        metadata={"prompt-metadata-key": "prompt-metadata-value"},
        tags=["prompt-tag1", "prompt-tag2"]
    )

    chain_message = messages.ChainMessage(
        experiment_info_=experiment_info_,
        chain_data={"chain-key": "chain-value"},
        duration="chain-duration",
        metadata={"chain-metadata-key": "chain-metadata-value"},
        tags=["chain-tag1", "chain-tag2"],
        others={"other-key": "other-value"},
    )

    tested.process(prompt_message)
    tested.process(chain_message)

    # Read messages from the disk, compare with the original ones
    filenames = glob.glob(f"{offline_directory}/*")

    for filename in filenames:
        with open(file=filename, mode="r") as in_stream:
            lines = in_stream.readlines()

        assert len(lines) == 2
        prompt_line, chain_line = lines[0], lines[1]

        assert messages.PromptMessage.from_dict(json.loads(prompt_line)["message"], api_key="api-key") == prompt_message
        assert messages.ChainMessage.from_dict(json.loads(chain_line)["message"], api_key="api-key") == chain_message