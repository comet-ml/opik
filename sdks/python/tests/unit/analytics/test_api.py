import pytest

from opik import analytics
from opik import config
from opik.analytics import api


def test_track_event__happyflow(recording_worker):
    analytics.track_event("client", "create_dataset")

    assert recording_worker.names == ["opik_python_sdk__client__create_dataset"]
    assert recording_worker.events[0].properties == {}


def test_track_event__properties__sent_as_given(recording_worker):
    analytics.track_event("evaluation", "metric_created", metric="Equals", count=2)

    assert recording_worker.events[0].properties == {"metric": "Equals", "count": 2}


def test_track_event__analytics_disabled__no_op(monkeypatch):
    monkeypatch.setattr(api, "_DISABLED", True)

    analytics.track_event("client", "create_dataset")


def test_track_event__worker_raises__exception_not_propagated(monkeypatch):
    class BrokenWorker:
        def enqueue(self, event):
            raise ValueError("boom")

    monkeypatch.setattr(api, "_WORKER", BrokenWorker())
    monkeypatch.setattr(api, "_DISABLED", False)
    monkeypatch.setattr(api, "_ALREADY_REPORTED", set())

    analytics.track_event("client", "create_dataset")


def test_track_event__same_event_repeated__reported_once(recording_worker):
    for _ in range(5):
        analytics.track_event("client", "create_dataset")

    assert recording_worker.names == ["opik_python_sdk__client__create_dataset"]


def test_track_event__same_action_different_properties__reported_separately(
    recording_worker,
):
    analytics.track_event("evaluation", "metric_created", metric="Equals")
    analytics.track_event("evaluation", "metric_created", metric="Contains")
    analytics.track_event("evaluation", "metric_created", metric="Equals")

    assert [event.properties["metric"] for event in recording_worker.events] == [
        "Equals",
        "Contains",
    ]


@pytest.mark.parametrize(
    "path, expected",
    [
        (("client", "create_dataset"), "opik_python_sdk__client__create_dataset"),
        (("evaluation", "evaluate"), "opik_python_sdk__evaluation__evaluate"),
        (("integration", "openai"), "opik_python_sdk__integration__openai"),
        (
            ("integration", "bedrock", "invoke_agent"),
            "opik_python_sdk__integration__bedrock__invoke_agent",
        ),
        (
            ("client", "prompt", "chat", "create"),
            "opik_python_sdk__client__prompt__chat__create",
        ),
    ],
)
def test_track_event__event_name__composed_by_joining_the_path(
    recording_worker, path, expected
):
    analytics.track_event(*path)

    assert recording_worker.names == [expected]


def test_track_event__name__splits_back_into_the_path(recording_worker):
    analytics.track_event("integration", "bedrock", "invoke_agent")

    name = recording_worker.names[0]
    assert name == "opik_python_sdk__integration__bedrock__invoke_agent"
    assert name.split("__") == [
        "opik_python_sdk",
        "integration",
        "bedrock",
        "invoke_agent",
    ]


def test_track_event__paths_differing_only_in_depth__get_different_names(
    recording_worker,
):
    """
    Joining with a single "_" would give these two the same name, since segments
    contain single underscores themselves.
    """
    analytics.track_event("integration", "bedrock", "invoke_agent")
    analytics.track_event("integration", "bedrock_invoke_agent")

    assert recording_worker.names == [
        "opik_python_sdk__integration__bedrock__invoke_agent",
        "opik_python_sdk__integration__bedrock_invoke_agent",
    ]


def _call_from_module(module_name, function):
    """Calls `function` from a frame that claims to live in `module_name`."""
    namespace = {"function": function, "__name__": module_name}
    exec("def caller():\n    function()\n", namespace)
    namespace["caller"]()


def _report_search_threads():
    """Stands in for `Opik.search_threads`, which reports and is also used internally."""
    analytics.track_event("client", "search_threads")


def test_track_event__reporting_function_called_by_another_opik_module__not_reported(
    recording_worker,
):
    _call_from_module(
        "opik.evaluation.threads.evaluation_engine", _report_search_threads
    )

    assert recording_worker.names == []


def test_track_event__reporting_function_called_by_user_code__reported(
    recording_worker,
):
    _call_from_module("my_app.pipeline", _report_search_threads)

    assert recording_worker.names == ["opik_python_sdk__client__search_threads"]


def test_track_event__internal_call_first__does_not_suppress_the_user_call(
    recording_worker,
):
    """
    An internal call must record nothing. Reporting happens once per process, so if
    the internal one counted, the user's own call would be deduped away.
    """
    _call_from_module(
        "opik.evaluation.threads.evaluation_engine", _report_search_threads
    )
    _call_from_module("my_app.pipeline", _report_search_threads)

    assert recording_worker.names == ["opik_python_sdk__client__search_threads"]


def _define_in_module(module_name, source):
    namespace = {"analytics": analytics, "__name__": module_name}
    exec(source, namespace)
    return namespace


def test_track_event__one_reported_call_nested_in_another__only_the_outer_reported(
    recording_worker,
):
    """
    `Opik.get_or_create_dataset` calls `self.get_dataset`, both in the same module.
    Comparing modules cannot see that, so the nested call is recognised by finding a
    reporting function further up the stack.
    """
    namespace = _define_in_module(
        "opik.api_objects.opik_client",
        "def get_dataset():\n"
        "    analytics.track_event('client', 'get_dataset')\n"
        "def get_or_create_dataset():\n"
        "    analytics.track_event('client', 'get_or_create_dataset')\n"
        "    get_dataset()\n",
    )

    _call_from_module("my_app.pipeline", namespace["get_or_create_dataset"])

    assert recording_worker.names == ["opik_python_sdk__client__get_or_create_dataset"]


def test_track_event__nested_call__still_reported_when_the_user_makes_it_directly(
    recording_worker,
):
    namespace = _define_in_module(
        "opik.api_objects.opik_client",
        "def get_dataset():\n"
        "    analytics.track_event('client', 'get_dataset')\n"
        "def get_or_create_dataset():\n"
        "    analytics.track_event('client', 'get_or_create_dataset')\n"
        "    get_dataset()\n",
    )

    _call_from_module("my_app.pipeline", namespace["get_or_create_dataset"])
    _call_from_module("my_app.pipeline", namespace["get_dataset"])

    assert recording_worker.names == [
        "opik_python_sdk__client__get_or_create_dataset",
        "opik_python_sdk__client__get_dataset",
    ]


def test_track_event__private_helper_in_the_same_module__reported(recording_worker):
    """
    `BaseMetric.__init__` reports through a helper next to it. That is still the
    user's call, not Opik using its own API.
    """
    module = "opik.evaluation.metrics.base_metric"
    namespace = {"analytics": analytics, "__name__": module}
    exec(
        "def _track_metric_creation():\n"
        "    analytics.track_event('evaluation', 'metric_created', metric='Equals')\n"
        "def __init__():\n"
        "    _track_metric_creation()\n",
        namespace,
    )
    _call_from_module("my_app.pipeline", namespace["__init__"])

    assert recording_worker.names == ["opik_python_sdk__evaluation__metric_created"]


def test_track_event__paths_sharing_a_prefix__reported_separately(recording_worker):
    """
    A deeper path is a different event, not a repeat of the shorter one - otherwise
    instrumenting part of a feature would silence the feature itself.
    """
    analytics.track_event("integration", "bedrock")
    analytics.track_event("integration", "bedrock", "invoke_agent")

    assert recording_worker.names == [
        "opik_python_sdk__integration__bedrock",
        "opik_python_sdk__integration__bedrock__invoke_agent",
    ]


def test_track_event__queue_full__event_not_left_claimed(recording_worker, monkeypatch):
    """
    Claiming happens before the hand-off. If the hand-off is refused the claim has to
    go with it, or the event is lost for the rest of the process.
    """

    class FullWorker:
        def enqueue(self, event):
            return False

    monkeypatch.setattr(api, "_WORKER", FullWorker())
    analytics.track_event("client", "create_dataset")

    assert api._ALREADY_REPORTED == set()

    # A later call, once the queue has drained, still reports it.
    monkeypatch.setattr(api, "_WORKER", recording_worker)
    analytics.track_event("client", "create_dataset")

    assert recording_worker.names == ["opik_python_sdk__client__create_dataset"]


@pytest.mark.parametrize("action", [None, 123, object(), b"bytes"])
def test_track_event__action_is_not_a_string__does_not_raise(action, recording_worker):
    """
    `track_event` runs inside the user-facing methods it reports on, so a bad call
    site must degrade to reporting nothing rather than breaking the method. Composing
    the name is where that used to escape.
    """
    api.track_event("client", action)

    assert recording_worker.names == []


class TestReportingAllowed:
    """The question a call site asks before doing work to enrich an event.

    Enrichment can cost a round-trip, so `OPIK_ANALYTICS_ENABLE=false` has to
    switch that off too, not just the sending.
    """

    def test_reporting_allowed__happyflow(self, monkeypatch):
        # Every "yes" this can answer has to be arranged explicitly, because the
        # answer is read from the environment the suite itself runs in: CI sets
        # OPIK_ANALYTICS_ENABLE=false, pytest is a rule of its own, and being
        # switched off for good is process-wide state that an earlier test asking
        # for a worker is enough to have set.
        monkeypatch.setenv("OPIK_ANALYTICS_ENABLE", "true")
        monkeypatch.setattr(api.rules.environment, "in_pytest", lambda: False)
        monkeypatch.setattr(api, "_DISABLED", False)

        assert analytics.reporting_allowed() is True

    def test_reporting_allowed__rules_say_no__is_false(self):
        """Running under pytest is one of those rules."""
        assert analytics.reporting_allowed() is False

    def test_reporting_allowed__already_disabled__is_false(self, monkeypatch):
        monkeypatch.setattr(api.rules.environment, "in_pytest", lambda: False)
        monkeypatch.setattr(api, "_DISABLED", True)

        assert analytics.reporting_allowed() is False

    def test_reporting_allowed__config_unreadable__is_false(self, monkeypatch):
        def broken():
            raise ValueError("boom")

        monkeypatch.setattr(api.config, "OpikConfig", broken)

        assert analytics.reporting_allowed() is False


def test_reporting_allowed__no_analytics_url__is_false(monkeypatch):
    """A missing destination has to refuse enrichment, not just sending.

    `_start_worker` already gives up without a URL, so a call site that pays for
    a lookup before reporting would be doing it for an event that is dropped.
    """
    monkeypatch.setattr(api.rules.environment, "in_pytest", lambda: False)
    monkeypatch.setattr(
        api.config, "OpikConfig", lambda: config.OpikConfig(analytics_url="")
    )

    assert analytics.reporting_allowed() is False
