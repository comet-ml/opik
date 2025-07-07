import logging
from typing import Optional, Tuple, Dict, Any
import google.adk.agents
from google.adk.agents import callback_context

LOGGER = logging.getLogger(__name__)


def try_get_session_info(
    callback_context: callback_context.CallbackContext,
) -> Tuple[Optional[str], Dict[str, Any]]:
    try:
        session = callback_context._invocation_context.session
        return session.id, {"user_id": session.user_id, "app_name": session.app_name}
    except Exception:
        LOGGER.error(
            "Failed to get session information from ADK callback context", exc_info=True
        )
        return None, {}


def try_get_current_agent_instance(
    callback_context: callback_context.CallbackContext,
) -> Optional[google.adk.agents.BaseAgent]:
    try:
        invocation_context = callback_context._invocation_context
        return invocation_context.agent
    except Exception:
        LOGGER.error(
            "Failed to get agent information from ADK callback context", exc_info=True
        )
        return None
