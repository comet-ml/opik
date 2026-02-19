"""Utility functions for Opik operations."""

from .logger_config import logger
from .opik_backend_client import OpikBackendClient

FEEDBACK_SCORE_NAME = "opikassist_user_feedback"


async def get_project_name_from_trace_id(
    opik_client: OpikBackendClient, trace_id: str
) -> str:
    """
    Get the project name from the trace id.

    Args:
        opik_client: The Opik backend client instance
        trace_id: The trace ID

    Returns:
        The project name
    """
    return await opik_client.get_project_name_from_trace(trace_id)


async def submit_feedback_to_opik(
    opik_client: OpikBackendClient,
    session_id: str,
    feedback_value: int,
    project_id: str,
) -> None:
    """
    Submit feedback score to an Opik thread (agent conversation).
    The thread must be closed before feedback can be submitted.

    Args:
        opik_client: The Opik backend client instance
        session_id: The session ID which corresponds to the Opik thread ID
        feedback_value: The feedback value (0 or 1)
        project_id: The project ID (UUID)

    Raises:
        Exception: If the Opik API call fails
    """
    try:
        # First, close the thread (required before submitting feedback)
        await opik_client.close_thread(thread_id=session_id, project_id=project_id)

        # Then submit feedback score to the thread
        scores = [
            {
                "project_id": project_id,
                "thread_id": session_id,
                "name": FEEDBACK_SCORE_NAME,
                "value": float(feedback_value),
                "source": "ui",
            }
        ]

        await opik_client.log_thread_feedback_scores(scores=scores)

        logger.info(
            f"Successfully submitted feedback {feedback_value} for session {session_id}"
        )

    except Exception as e:
        logger.error(f"Failed to submit feedback to Opik for session {session_id}: {e}")
        raise


async def delete_feedback_from_opik(
    opik_client: OpikBackendClient,
    session_id: str,
    trace_id: str,
) -> None:
    """
    Delete feedback score from an Opik thread (agent conversation).

    Args:
        opik_client: The Opik backend client instance
        session_id: The session ID which corresponds to the Opik thread ID
        trace_id: The trace ID (needed to resolve project_name)

    Raises:
        Exception: If the Opik API call fails or feedback not found
    """
    try:
        project_name = await opik_client.get_project_name_from_trace(trace_id)

        await opik_client.delete_thread_feedback_scores(
            project_name=project_name,
            thread_id=session_id,
            names=[FEEDBACK_SCORE_NAME],
        )

        logger.info(f"Successfully deleted feedback for session {session_id}")

    except Exception as e:
        logger.error(
            f"Failed to delete feedback from Opik for session {session_id}: {e}"
        )
        raise
