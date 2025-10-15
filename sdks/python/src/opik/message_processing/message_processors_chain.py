from . import (
    message_processors,
    online_message_processor,
)
from .emulation import local_emulator_message_processor
from ..rest_api import client as rest_api_client


def create_message_processors_chain(
    rest_client: rest_api_client.OpikApi,
) -> message_processors.ChainedMessageProcessor:
    online = online_message_processor.OpikMessageProcessor(rest_client=rest_client)
    # is not active by default - will be activated during evaluation
    local = local_emulator_message_processor.LocalEmulatorMessageProcessor(active=False)
    return message_processors.ChainedMessageProcessor(processors=[online, local])
