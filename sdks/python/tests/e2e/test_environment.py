"""End-to-end tests for the SDK ``environment`` feature.

Exercises:
- Environment CRUD round-trip via the Opik client.
- Per-call ``environment=`` on ``Opik.trace`` is persisted on the trace, and
  spans (manual + ``@track``) inherit that value from the parent trace.
- Backwards compat: existing call sites without environment still log successfully.
"""

from typing import Dict

import opik
from opik import id_helpers
from . import verifiers
from ..testlib import generate_project_name


PROJECT_NAME = generate_project_name("e2e", __name__)


def test_environment_crud__round_trip(opik_client: opik.Opik, environment_name: str):
    created = opik_client.create_environment(
        name=environment_name,
        description="created by SDK e2e test",
        color="#abcdef",
    )
    assert created.name == environment_name
    assert created.description == "created by SDK e2e test"
    assert created.color == "#abcdef"
    assert created.id is not None

    listed = opik_client.get_environments()
    assert any(env.name == environment_name for env in listed)

    updated = opik_client.update_environment(
        environment_name, description="updated by SDK e2e test"
    )
    assert updated.description == "updated by SDK e2e test"
    assert updated.name == environment_name

    opik_client.delete_environment(environment_name)

    # Calling delete on a missing name is a no-op.
    opik_client.delete_environment(environment_name)


def test_trace__environment_kwarg_is_persisted_on_the_trace(
    opik_client: opik.Opik, environment_name: str
):
    opik_client.create_environment(name=environment_name)

    trace_id = id_helpers.generate_id()
    opik_client.trace(
        id=trace_id,
        name="env-trace",
        project_name=PROJECT_NAME,
        environment=environment_name,
    )
    opik_client.flush()

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace_id,
        project_name=PROJECT_NAME,
        environment=environment_name,
    )


def test_span__inherits_trace_environment_and_ignores_per_call_override(
    opik_client: opik.Opik, environment_name: str
):
    """A span persists its parent trace's environment. Once the trace is open
    with an environment, an inner ``@track`` cannot override it — the inner
    span's per-call ``environment=`` is ignored and it inherits the trace's
    environment."""
    opik_client.create_environment(name=environment_name)

    captured: Dict[str, str] = {}

    @opik.track(name="inner-fn", environment="this-should-be-ignored")
    def inner():
        ctx = opik.opik_context.get_current_span_data()
        captured["span_id"] = ctx.id
        captured["trace_id"] = ctx.trace_id
        captured["parent_span_id"] = ctx.parent_span_id

    @opik.track(name="outer-fn", environment=environment_name)
    def outer():
        inner()

    outer()
    opik.flush_tracker()

    verifiers.verify_span(
        opik_client=opik_client,
        span_id=captured["span_id"],
        trace_id=captured["trace_id"],
        parent_span_id=captured["parent_span_id"],
        environment=environment_name,
    )


def test_trace_without_environment__still_works_for_backwards_compat(
    opik_client: opik.Opik,
):
    trace_id = id_helpers.generate_id()
    opik_client.trace(id=trace_id, name="no-env-trace", project_name=PROJECT_NAME)
    opik_client.flush()

    verifiers.verify_trace(
        opik_client=opik_client, trace_id=trace_id, project_name=PROJECT_NAME
    )
