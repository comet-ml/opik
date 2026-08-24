"""
Usage analytics for the Opik Python SDK - which SDK features get used, so we know what
to invest in.

Only what a call site passes explicitly is ever sent, alongside the environment
(SDK/Python version, OS, installation type). Events are attributed to the workspace
name, the same identifier used for error reports. Trace, span, prompt and dataset
contents are never sent, nor is the API key.

Reporting happens on a background thread, is switched off by
`OPIK_ANALYTICS_ENABLE=false`, and never raises into calling code.
"""

from .api import Component, flush, internal, shutdown, track_event
from .rules import register_rule
from .worker import PropertyValue

__all__ = [
    "Component",
    "PropertyValue",
    "flush",
    "internal",
    "register_rule",
    "shutdown",
    "track_event",
]
