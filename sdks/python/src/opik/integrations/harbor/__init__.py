"""
Opik integration for Harbor benchmark evaluation framework.

Example:
    >>> from opik.integrations.harbor import track_harbor
    >>> job = Job(config)
    >>> tracked_job = track_harbor(job)
    >>> result = await tracked_job.run()

Or enable tracking globally (for CLI usage):
    >>> from opik.integrations.harbor import track_harbor
    >>> track_harbor()
"""

from .opik_tracker import track_harbor, reset_harbor_tracking

__all__ = ["track_harbor", "reset_harbor_tracking"]
