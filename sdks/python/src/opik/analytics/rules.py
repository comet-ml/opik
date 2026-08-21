import logging
from typing import Callable, List

from .. import config, environment

LOGGER = logging.getLogger(__name__)

Rule = Callable[[config.OpikConfig], bool]
"""Returns False to switch analytics off for the whole process."""


def _enabled_in_config(config_: config.OpikConfig) -> bool:
    """`OPIK_ANALYTICS_ENABLE=false` - the one way to switch reporting off."""
    return config_.analytics_enable


def _not_running_tests(config_: config.OpikConfig) -> bool:
    """
    Not a user-facing switch: it keeps test suites - Opik's own and everyone else's -
    from reporting, and from making network calls in the middle of a test run.
    """
    return not environment.in_pytest()


_RULES: List[Rule] = [
    _enabled_in_config,
    _not_running_tests,
]


def register_rule(rule: Rule) -> None:
    """
    Adds a reason to switch analytics off, e.g. to keep it out of a particular
    deployment. Rules are evaluated once, before anything is reported, so this has
    to be called before the first tracked event to have any effect.

    Example:
        >>> analytics.register_rule(lambda config: config.url_override != INTERNAL_URL)
    """
    _RULES.append(rule)


def reporting_allowed(config_: config.OpikConfig) -> bool:
    """True when every rule agrees analytics may run in this process."""
    for rule in _RULES:
        try:
            if not rule(config_):
                LOGGER.debug("Analytics disabled by rule %s", rule.__name__)
                return False
        except Exception:
            LOGGER.debug("Analytics rule %s failed, disabling analytics", rule.__name__)
            return False

    return True
