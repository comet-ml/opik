"""
Opik integration for Harbor benchmark evaluation framework.

Example:
    >>> from opik.integrations.harbor import track_harbor
    >>> job = Job(config)
    >>> tracked_job = track_harbor(job)
    >>> result = await tracked_job.run()

Or enable tracking globally:
    >>> from opik.integrations.harbor import enable_tracking
    >>> enable_tracking(project_name="my-project")
"""

from .opik_tracker import track_harbor, enable_tracking, reset_harbor_tracking

__all__ = ["track_harbor", "enable_tracking", "reset_harbor_tracking"]
