"""The demo seeder creates the test suite, its items, the prompt versions and the experiments.

The Opik client is a recording stand-in with the opik 2.x test suite API; the backend endpoints the
seeder calls directly (project lookup, agent config blueprints) are served by an HTTP stub. Any
error in the seeder is caught and only logged, so the test asserts what was created, not that
nothing raised.
"""
import itertools
import json
import uuid

import pytest
from pytest_httpserver import HTTPServer
from werkzeug import Response

from opik_backend import demo_data_generator
from opik_backend.demo_data import (
    demo_agent_prompt_name,
    demo_agent_prompt_v1,
    demo_agent_prompt_v2,
    demo_test_suite_experiments,
    demo_test_suite_global_assertion,
    demo_test_suite_items,
)

PROJECT_NAME = "Demo project"
PROJECT_ID = str(uuid.uuid4())
SUITE_NAME = "Customer support regression suite"


class RecordingSuite:
    def __init__(self, name):
        self._name = name
        self.inserted = []

    @property
    def name(self):
        return self._name

    def get_items(self, nb_samples=None, filter_string=None):
        return [{"id": f"item-{i}", **item} for i, item in enumerate(self.inserted)]

    def insert(self, items, deduplication=True):
        self.inserted.extend(items)


class RecordingRest:
    def __init__(self, calls):
        self.traces = self
        self.spans = self
        self.experiments = self
        self._calls = calls

    def create_traces(self, traces):
        self._calls.append(("create_traces", len(traces)))

    def create_spans(self, spans):
        self._calls.append(("create_spans", len(spans)))

    def create_experiment_items(self, experiment_items):
        self._calls.append(("create_experiment_items", {item.experiment_id for item in experiment_items},
                            len(experiment_items)))

    def score_batch_of_traces(self, scores):
        self._calls.append(("score_batch_of_traces", len(scores)))


class RecordingClient:
    """Only the opik 2.x methods the seeder uses, so a call to a removed one fails as it would for real."""

    instances = []

    def __init__(self, **kwargs):
        self.calls = []
        self.suites = []
        self.rest_client = RecordingRest(self.calls)
        self._commits = itertools.count(1)
        RecordingClient.instances.append(self)

    def get_or_create_test_suite(self, name, description=None, global_assertions=None,
                                 global_execution_policy=None, tags=None, project_name=None):
        self.calls.append(("get_or_create_test_suite", name, global_assertions, project_name))
        suite = RecordingSuite(name)
        self.suites.append(suite)
        return suite

    def create_prompt(self, name, prompt, metadata=None, type=None, id=None, description=None,
                      change_description=None, tags=None, project_name=None):
        commit = f"commit-{next(self._commits)}"
        self.calls.append(("create_prompt", name, prompt, project_name, commit))
        return type_of("Prompt", commit=commit)

    def create_experiment(self, dataset_name, name=None, evaluation_method="dataset", tags=None,
                          project_name=None, experiment_config=None, **ignored):
        experiment_id = f"experiment-{name}"
        self.calls.append(("create_experiment", name, dataset_name, evaluation_method, project_name))
        return type_of("Experiment", id=experiment_id)

    def flush(self):
        return True

    def end(self):
        pass


def type_of(name, **attributes):
    return type(name, (), attributes)()


@pytest.fixture
def backend(httpserver: HTTPServer):
    blueprints = itertools.count(1)
    latest = {}

    def create_blueprint(request):
        n = next(blueprints)
        latest.update(id=f"blueprint-{n}", name=f"v{n}")
        return _json_response({}, 201)

    httpserver.expect_request("/v1/private/projects/retrieve", method="POST").respond_with_json(
        {"id": PROJECT_ID, "name": PROJECT_NAME})
    httpserver.expect_request(f"/v1/private/agent-configs/blueprints/latest/projects/{PROJECT_ID}",
                              method="GET").respond_with_handler(
        lambda request: _json_response(latest, 200) if latest
        else _json_response({}, 404))
    httpserver.expect_request("/v1/private/agent-configs/blueprints", method="POST").respond_with_handler(create_blueprint)
    httpserver.expect_request("/v1/private/agent-configs/blueprints", method="PATCH").respond_with_handler(create_blueprint)
    httpserver.expect_request(f"/v1/private/agent-configs/blueprints/environments/prod/projects/{PROJECT_ID}",
                              method="PUT").respond_with_data(status=204)
    httpserver.expect_request(f"/v1/private/agent-configs/blueprints/environments/staging/projects/{PROJECT_ID}",
                              method="PUT").respond_with_data(status=204)
    return httpserver


def _json_response(body, status):
    return Response(json.dumps(body), status=status, content_type="application/json")


@pytest.fixture
def client(monkeypatch):
    RecordingClient.instances = []
    monkeypatch.setattr(demo_data_generator.opik, "Opik", RecordingClient)
    yield lambda: RecordingClient.instances[0]


def test_seeds_the_suite_its_items_prompt_versions_and_experiments(backend, client):
    demo_data_generator.create_demo_test_suite_and_experiments(
        backend.url_for("").rstrip("/"), "workspace", "api-key", PROJECT_NAME)

    actual = client()
    items = len(demo_test_suite_items)
    assert actual.calls[0] == ("get_or_create_test_suite", SUITE_NAME, [demo_test_suite_global_assertion],
                               PROJECT_NAME)
    assert actual.suites[0].inserted == [
        {"data": {"question": template["question"]}, "assertions": template["assertions"]}
        for template in demo_test_suite_items
    ]
    assert [call for call in actual.calls if call[0] == "create_prompt"] == [
        ("create_prompt", demo_agent_prompt_name, demo_agent_prompt_v1, PROJECT_NAME, "commit-1"),
        ("create_prompt", demo_agent_prompt_name, demo_agent_prompt_v2, PROJECT_NAME, "commit-2"),
    ]
    assert [call for call in actual.calls if call[0] == "create_experiment"] == [
        ("create_experiment", exp["name"], SUITE_NAME, "evaluation_suite", PROJECT_NAME)
        for exp in demo_test_suite_experiments
    ]
    # Per experiment: a trace per item, their spans, an experiment item per item and the assertion scores.
    writes = [call for call in actual.calls if call[0] in REST_WRITES]
    assert [call[0] for call in writes] == list(REST_WRITES) * len(demo_test_suite_experiments)
    assert [call for call in writes if call[0] in ("create_traces", "create_experiment_items")] == [
        call for exp in demo_test_suite_experiments for call in (
            ("create_traces", items),
            ("create_experiment_items", {f"experiment-{exp['name']}"}, items),
        )
    ]
    assert all(call[1] > items for call in writes if call[0] in ("create_spans", "score_batch_of_traces"))


REST_WRITES = ("create_traces", "create_spans", "create_experiment_items", "score_batch_of_traces")
