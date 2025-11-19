# This litellm integration is currently not exposed in the documentation and
# used as a part of Opik CrewAI integration only.
#
# TODO: in order to support it officially - implement streaming support, update documentation, make it
# respect the OpikLogger callback in litellm repository to avoid confusion.

from .opik_tracker import track_litellm

__all__ = ["track_litellm"]
