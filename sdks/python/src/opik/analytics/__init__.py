"""
Usage analytics for the Opik Python SDK - which SDK features get used, so we know what
to invest in.

Only what a call site passes explicitly is ever sent, alongside the environment
(SDK/Python version, OS, installation type). Events are attributed to the workspace
name, the same identifier used for error reports. Trace, span, prompt and dataset
contents are never sent, nor is the API key.

One exception, and it is deliberate: the two `configuration` events - `opik configure`
and `opik mcp configure` - also report the Comet login of the account being
configured, when there is one to resolve. It is the value the MCP server reports as
its own `user_id`, and the warehouse's own user key, so it is what ties a
configuration run to what that person went on to do; hashing it would make it
unjoinable, which is the whole point of reporting it. See
`opik.cli.account_identity`. No other event carries it, and no other personal data
goes with it.

Reporting happens on a background thread, is switched off by
`OPIK_ANALYTICS_ENABLE=false`, and never raises into calling code.
"""

from .api import (
    Component,
    flush,
    internal,
    reporting_allowed,
    shutdown,
    track_event,
)
from .rules import register_rule
from .worker import PropertyValue

__all__ = [
    "Component",
    "PropertyValue",
    "flush",
    "internal",
    "register_rule",
    "reporting_allowed",
    "shutdown",
    "track_event",
]
