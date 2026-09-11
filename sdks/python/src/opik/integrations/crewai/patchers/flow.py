"""
Patcher for CrewAI Flow class (v1.0.0+).

This module patches the Flow class to automatically track flow methods and execution.
"""

import functools
import logging
from typing import Optional

import opik.decorator.tracker as opik_tracker

LOGGER = logging.getLogger(__name__)


def patch_flow(project_name: Optional[str] = None) -> None:
    """
    Patches CrewAI Flow class to track flow execution.

    If Flow class is not available (CrewAI < v1.0.0), this function does nothing.

    Args:
        project_name: The name of the project to associate with tracking.
    """
    _patch_flow_init(project_name)
    _patch_flow_kickoff_async(project_name)


def _patch_flow_init(project_name: Optional[str] = None) -> None:
    """
    Patches CrewAI Flow.__init__ to automatically track flow methods.

    If Flow class is not available (CrewAI < v1.0.0), this function does nothing.
    """
    try:
        import crewai

        if not hasattr(crewai, "Flow"):
            LOGGER.debug("CrewAI Flow class not available, skipping Flow patching")
            return

        if hasattr(_patch_flow_init, "_patched"):
            return

        original_init = crewai.Flow.__init__

        @functools.wraps(original_init)
        def _init_wrapper(self, *args, **kwargs) -> None:  # type: ignore
            original_init(self, *args, **kwargs)

            try:
                flow_registered_methods = getattr(self, "_methods", {})
                for method_name, method in list(flow_registered_methods.items()):
                    if getattr(method, "opik_tracked", False):
                        continue

                    decorated = opik_tracker.track(
                        project_name=project_name,
                        tags=["crewai"],
                        metadata={"created_from": "crewai"},
                    )(method)

                    flow_registered_methods[method_name] = decorated
            except Exception:
                LOGGER.error(
                    "An error occurred during Opik instrumentation of CrewAI Flow",
                    exc_info=True,
                )

        crewai.Flow.__init__ = _init_wrapper  # type: ignore[assignment]

        setattr(_patch_flow_init, "_patched", True)  # type: ignore[attr-defined]
    except (ImportError, AttributeError):
        LOGGER.debug(
            "CrewAI Flow class not available, skipping Flow patching", exc_info=True
        )


def _patch_flow_kickoff_async(project_name: Optional[str] = None) -> None:
    """
    Patches CrewAI Flow.kickoff_async to track flow execution.

    If Flow class is not available (CrewAI < v1.0.0), this function does nothing.
    """
    try:
        import crewai

        if not hasattr(crewai, "Flow"):
            LOGGER.debug(
                "CrewAI Flow class not available, skipping Flow.kickoff_async patching"
            )
            return

        if hasattr(_patch_flow_kickoff_async, "_patched"):
            return

        # We only need to patch the async version of the kickoff method because
        # the sync version calls it internally
        original_kickoff_async = crewai.Flow.kickoff_async

        @functools.wraps(original_kickoff_async)
        async def _kickoff_async_wrapper(self, *args, **kwargs):  # type: ignore
            wrapped = opik_tracker.track(
                project_name=project_name,
                tags=["crewai"],
                name="Flow.kickoff_async",
                metadata={"created_from": "crewai"},
            )(original_kickoff_async)
            return await wrapped(self, *args, **kwargs)

        crewai.Flow.kickoff_async = _kickoff_async_wrapper  # type: ignore[assignment]

        setattr(_patch_flow_kickoff_async, "_patched", True)  # type: ignore[attr-defined]
    except (ImportError, AttributeError):
        LOGGER.debug(
            "CrewAI Flow class not available, skipping Flow.kickoff_async patching",
            exc_info=True,
        )
