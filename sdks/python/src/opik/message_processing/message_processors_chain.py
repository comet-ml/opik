import logging
from typing import Optional

from . import (
    message_processors,
    online_message_processor,
)
from .emulation import local_emulator_message_processor
from ..rest_api import client as rest_api_client


LOGGER = logging.getLogger(__name__)


def create_message_processors_chain(
    rest_client: rest_api_client.OpikApi,
) -> message_processors.ChainedMessageProcessor:
    """
    Creates a chain of message processors by combining an online processor and a
    local emulator processor. The chain is primarily useful for processing messages
    in a sequence where each processor in the chain contributes its functionality.

    The online processor is initialized using the provided REST API client. The local
    emulator processor is included but remains inactive by default. The constructed
    chain ensures combined and streamlined processing, accommodating both online
    and local simulation needs based on evaluation activation.

    Args:
        rest_client: REST API client instance used to configure the online message
            processor.

    Returns:
        A chained message processor containing the online and local emulator processors.
    """
    online = online_message_processor.OpikMessageProcessor(rest_client=rest_client)
    # is not active by default - will be activated during evaluation
    local = local_emulator_message_processor.LocalEmulatorMessageProcessor(active=False)

    return message_processors.ChainedMessageProcessor(processors=[online, local])


def toggle_local_emulator_message_processor(
    active: bool, chain: message_processors.ChainedMessageProcessor, reset: bool = True
) -> None:
    """
    Toggles the state of the Local Emulator Message Processor within a given
    ChainedMessageProcessor. This function either activates or deactivates the
    processor based on the `active` parameter and resets its state if being
    activated. Logs a warning if the Local Emulator Message Processor is not
    found in the chain.

    Args:
        active: Determines whether to activate or deactivate the Local
            Emulator Message Processor. If True, the processor is activated.
        chain: The message processor
            chain containing the Local Emulator Message Processor to be toggled.
        reset: Determines whether to reset the Local Emulator Message Processor.
            This can be used to clear the state of the Local Emulator before
            evaluation. Also, it can be used to clean up the state of the Local Emulator
            after evaluation to release system resources (memory).
    """
    local = chain.get_processor_by_type(
        local_emulator_message_processor.LocalEmulatorMessageProcessor
    )
    if local is None:
        LOGGER.warning("Local emulator message processor not found in the chain.")
        return

    if reset:
        local.reset()

    local.set_active(active=active)


def get_local_emulator_message_processor(
    chain: message_processors.ChainedMessageProcessor,
) -> Optional[local_emulator_message_processor.LocalEmulatorMessageProcessor]:
    """
    Retrieves the local emulator message processor from a given chain of message processors.

    This function searches through the provided chain and looks for a processor of type
    LocalEmulatorMessageProcessor. If one is found, it is returned; otherwise, None is returned.

    Args:
        chain: A chain of message processors that may contain a
            LocalEmulatorMessageProcessor.

    Returns:
        The LocalEmulatorMessageProcessor if found in the chain,
        otherwise None.
    """
    local = chain.get_processor_by_type(
        local_emulator_message_processor.LocalEmulatorMessageProcessor
    )
    return local
