"""
Starting and stopping the reporting machinery.

`_start_worker` runs once, lazily, on the first tracked event, and holds `_LOCK`
while it decides. Everything here is about the edges of that: refusing to start when
there is nothing to talk to, and not starting after something has already stopped it.
"""

import pytest

from opik import config
from opik.analytics import api


@pytest.fixture
def unstarted(monkeypatch):
    """A process that has not reported yet, with the rules out of the way."""
    monkeypatch.setattr(api, "_WORKER", None)
    monkeypatch.setattr(api, "_SENDER", None)
    monkeypatch.setattr(api, "_DISABLED", False)
    monkeypatch.setattr(api.rules, "reporting_allowed", lambda config_: True)
    return monkeypatch


def _config_with(url):
    """
    A real config with the destination overridden. `OpikConfig` is replaced for the
    whole SDK by these tests, and plenty beyond `_start_worker` reads it - a stub
    carrying only the fields this file cares about breaks those instead.
    """
    real_config = config.OpikConfig  # captured before the patch replaces it
    return lambda **_: real_config(analytics_url=url)


def test_start_worker__no_destination_configured__no_thread_started(unstarted):
    """
    Not a second opt-out - `OPIK_ANALYTICS_ENABLE` is that. An empty URL would make
    every batch fail, so there is no point starting a thread to produce them.
    """
    unstarted.setattr(api.config, "OpikConfig", _config_with(""))

    assert api._start_worker() is None
    assert api._WORKER is None
    assert api._DISABLED is True


def test_start_worker__shutdown_lands_while_rules_run__no_worker_published(unstarted):
    """
    `shutdown` gives up on `_LOCK` after a couple of seconds, so it can switch
    reporting off while `_start_worker` is still inside the rules - which are
    arbitrary user code and may be slow. Publishing a worker after that point undoes
    the shutdown and leaves a live thread and an open connection pool behind it.

    The race is forced rather than raced for: the rule flips `_DISABLED` itself,
    which is exactly the state `_start_worker` would find on the far side of a slow
    one.
    """
    closed = []

    class Sender:
        def __init__(self, url):
            self.url = url

        def close(self):
            closed.append(True)

        def send(self, events):
            raise AssertionError("must never send after shutdown")

    def rule_that_races_shutdown(config_):
        api._DISABLED = True
        return True

    unstarted.setattr(api.comet_stats, "Sender", Sender)
    unstarted.setattr(api.rules, "reporting_allowed", rule_that_races_shutdown)
    unstarted.setattr(
        api.config, "OpikConfig", _config_with("http://collector.invalid")
    )

    assert api._start_worker() is None
    assert api._WORKER is None
    assert api._SENDER is None
    # and the pool opened on the way there is released rather than orphaned
    assert closed == [True]
