import logging

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
    active: bool, chain: message_processors.ChainedMessageProcessor
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
    """
    local = chain.get_processor_by_type(
        local_emulator_message_processor.LocalEmulatorMessageProcessor
    )
    if local is None:
        LOGGER.warning("Local emulator message processor not found in the chain.")
        return

    if active:
        # reset the local emulator state to make it ready for the next evaluation
        local.reset()

    local.set_active(active=active)


def reset_local_emulator_message_processor(
    chain: message_processors.ChainedMessageProcessor,
) -> None:
    """
    Resets the local emulator message processor within a message processing chain. This function searches for
    the relevant local emulator message processor in the provided chain and invokes its reset method. If the
    processor is not found, a warning is logged without performing any changes.

    Args:
        chain: The chain of message processors in which the
            local emulator message processor will be searched and reset.
    """
    local = chain.get_processor_by_type(
        local_emulator_message_processor.LocalEmulatorMessageProcessor
    )
    if local is None:
        LOGGER.warning("Local emulator message processor not found in the chain.")
        return

    local.reset()
