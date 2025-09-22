import logging
import crewai
import functools
from opik.decorator import tracker as opik_tracker
from typing import Optional

LOGGER = logging.getLogger(__name__)


def patch_flow_init(project_name: Optional[str] = None) -> None:
    original_init = crewai.Flow.__init__
    if hasattr(patch_flow_init, "_patched"):
        return

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

    setattr(patch_flow_init, "_patched", True)  # type: ignore[attr-defined]


def patch_flow_kickoff_async(project_name: Optional[str] = None) -> None:
    # We only need to patch the async version of the kickoff method because
    # the sync version calls it internally
    original_kickoff_async = crewai.Flow.kickoff_async
    if hasattr(patch_flow_kickoff_async, "_patched"):
        return

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

    setattr(patch_flow_kickoff_async, "_patched", True)  # type: ignore[attr-defined]
