"""
Analytics utilities for tracking user interactions with Segment.
"""

from typing import Any, Dict, Optional

import segment.analytics as analytics

from .config import settings
from .logger_config import logger

# Initialize Segment Analytics client
analytics_initialized = False

if settings.segment_write_key:
    try:
        analytics.write_key = settings.segment_write_key
        analytics_initialized = True
        logger.info("Segment Analytics client initialized successfully")
    except Exception as e:
        logger.error(f"Failed to initialize Segment Analytics client: {e}")
        analytics_initialized = False
else:
    logger.info("Segment Analytics not configured - skipping initialization")


def track_conversation_started(
    user_id: str,
    workspace_name: str,
    trace_id: str,
    project_name: Optional[str] = None,
    properties: Optional[Dict[str, Any]] = None,
) -> None:
    """
    Track when a user starts a new conversation.

    Args:
        user_id: The user's unique identifier
        workspace_name: The workspace name
        trace_id: The trace ID for the conversation
        project_name: Optional project name
        properties: Additional properties to track
    """
    if not analytics_initialized:
        logger.debug("Segment Analytics not configured - skipping event tracking")
        return

    try:
        event_properties = {
            "workspace_name": workspace_name,
            "trace_id": trace_id,
            "environment": settings.segment_environment,
        }

        if project_name:
            event_properties["project_name"] = project_name

        if properties:
            event_properties.update(properties)

        analytics.track(
            user_id=user_id,
            event="opik-ai-trace-analyzer-conversation-started",
            properties=event_properties,
        )

        logger.info(
            f"Tracked conversation started for user {user_id}, trace {trace_id}"
        )

    except Exception as e:
        logger.error(f"Failed to track conversation started event: {e}")


def track_conversation_resumed(
    user_id: str,
    workspace_name: str,
    trace_id: str,
    project_name: Optional[str] = None,
    message_count: Optional[int] = None,
    properties: Optional[Dict[str, Any]] = None,
) -> None:
    """
    Track when a user resumes an existing conversation.

    Args:
        user_id: The user's unique identifier
        workspace_name: The workspace name
        trace_id: The trace ID for the conversation
        project_name: Optional project name
        message_count: Number of existing messages in the conversation
        properties: Additional properties to track
    """
    if not analytics_initialized:
        logger.debug("Segment Analytics not configured - skipping event tracking")
        return

    try:
        event_properties = {
            "workspace_name": workspace_name,
            "trace_id": trace_id,
            "environment": settings.segment_environment,
        }

        if project_name:
            event_properties["project_name"] = project_name

        if message_count is not None:
            event_properties["message_count"] = message_count

        if properties:
            event_properties.update(properties)

        analytics.track(
            user_id=user_id,
            event="opik-ai-trace-analyzer-conversation-resumed",
            properties=event_properties,
        )

        logger.info(
            f"Tracked conversation resumed for user {user_id}, trace {trace_id}"
        )

    except Exception as e:
        logger.error(f"Failed to track conversation resumed event: {e}")


def track_feedback_submitted(
    user_id: str,
    workspace_name: str,
    trace_id: str,
    feedback_value: int,
    feedback_action: str,
    project_name: Optional[str] = None,
    properties: Optional[Dict[str, Any]] = None,
) -> None:
    """
    Track when a user submits, updates, or deletes feedback for an OpikAssist conversation.

    Args:
        user_id: The user's unique identifier
        workspace_name: The workspace name
        trace_id: The trace ID for the conversation
        feedback_value: The feedback value (0 or 1, -1 for deletion)
        feedback_action: The action taken ("created", "updated", or "deleted")
        project_name: Optional project name
        properties: Additional properties to track
    """
    if not analytics_initialized:
        logger.debug("Segment Analytics not configured - skipping event tracking")
        return

    try:
        event_properties = {
            "workspace_name": workspace_name,
            "trace_id": trace_id,
            "feedback_value": feedback_value,
            "feedback_action": feedback_action,
            "environment": settings.segment_environment,
        }

        if project_name:
            event_properties["project_name"] = project_name

        if properties:
            event_properties.update(properties)

        analytics.track(
            user_id=user_id,
            event="opik-ai-trace-analyzer-feedback-submitted",
            properties=event_properties,
        )

        logger.info(
            f"Tracked feedback {feedback_action} for user {user_id}, trace {trace_id}, value {feedback_value}"
        )

    except Exception as e:
        logger.error(f"Failed to track feedback submitted event: {e}")


def flush_events() -> None:
    """
    Flush any pending analytics events to Segment.
    This is useful for ensuring events are sent before the application shuts down.
    """
    if analytics_initialized:
        try:
            analytics.flush()
            logger.info("Flushed pending analytics events to Segment")
        except Exception as e:
            logger.error(f"Failed to flush analytics events: {e}")
