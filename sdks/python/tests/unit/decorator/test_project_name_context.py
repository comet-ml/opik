import asyncio
import logging

from opik import context_storage
from opik.api_objects import opik_client
from opik.decorator import tracker
from ...testlib import (
    ANY_BUT_NONE,
    SpanModel,
    TraceModel,
    assert_equal,
)


def test_track__project_name_set_on_decorator__trace_and_span_use_that_project(
    fake_backend,
):
    @tracker.track(project_name="custom-project")
    def f(x):
        return "output"

    f("input")
    tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert_equal(
        TraceModel(
            id=ANY_BUT_NONE,
            name="f",
            input={"x": "input"},
            output={"output": "output"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            project_name="custom-project",
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="f",
                    input={"x": "input"},
                    output={"output": "output"},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name="custom-project",
                    spans=[],
                )
            ],
        ),
        fake_backend.trace_trees[0],
    )


def test_track__project_name_set_on_decorator__context_var_available_inside_and_reset_after(
    fake_backend,
):
    captured_project = None

    @tracker.track(project_name="agent-project")
    def f(x):
        nonlocal captured_project
        captured_project = context_storage.get_context_project_name()
        return "output"

    f("input")
    tracker.flush_tracker()

    assert captured_project == "agent-project"
    assert context_storage.get_context_project_name() is None


def test_track__nested_functions__child_inherits_project_from_parent(
    fake_backend,
):
    captured_inner_project = None

    @tracker.track
    def f_inner(x):
        nonlocal captured_inner_project
        captured_inner_project = context_storage.get_context_project_name()
        return "inner-output"

    @tracker.track(project_name="parent-project")
    def f_outer(x):
        f_inner("inner-input")
        return "outer-output"

    f_outer("outer-input")
    tracker.flush_tracker()

    assert captured_inner_project == "parent-project"
    assert len(fake_backend.trace_trees) == 1
    assert_equal(
        TraceModel(
            id=ANY_BUT_NONE,
            name="f_outer",
            input={"x": "outer-input"},
            output={"output": "outer-output"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            project_name="parent-project",
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="f_outer",
                    input={"x": "outer-input"},
                    output={"output": "outer-output"},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name="parent-project",
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            name="f_inner",
                            input={"x": "inner-input"},
                            output={"output": "inner-output"},
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            project_name="parent-project",
                            spans=[],
                        )
                    ],
                )
            ],
        ),
        fake_backend.trace_trees[0],
    )


def test_track__nested_decorator_with_different_project__outer_sticks_and_warns(
    fake_backend,
    capfd,
):
    captured_inner_project = None

    @tracker.track(project_name="inner-project")
    def f_inner(x):
        nonlocal captured_inner_project
        captured_inner_project = context_storage.get_context_project_name()
        return "inner-output"

    @tracker.track(project_name="outer-project")
    def f_outer(x):
        f_inner("inner-input")
        return "outer-output"

    f_outer("outer-input")
    tracker.flush_tracker()

    assert captured_inner_project == "outer-project"
    assert len(fake_backend.trace_trees) == 1
    assert_equal(
        TraceModel(
            id=ANY_BUT_NONE,
            name="f_outer",
            input={"x": "outer-input"},
            output={"output": "outer-output"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            project_name="outer-project",
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="f_outer",
                    input={"x": "outer-input"},
                    output={"output": "outer-output"},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name="outer-project",
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            name="f_inner",
                            input={"x": "inner-input"},
                            output={"output": "inner-output"},
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            project_name="outer-project",
                            spans=[],
                        )
                    ],
                )
            ],
        ),
        fake_backend.trace_trees[0],
    )

    captured = capfd.readouterr()
    assert "inner-project" in captured.err and "outer-project" in captured.err


def test_track__nested_decorator_with_same_project__no_warning(
    fake_backend,
    capfd,
):
    @tracker.track(project_name="same-project")
    def f_inner(x):
        return "inner-output"

    @tracker.track(project_name="same-project")
    def f_outer(x):
        f_inner("inner-input")
        return "outer-output"

    f_outer("outer-input")
    tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].project_name == "same-project"

    captured = capfd.readouterr()
    assert "Attempted to set project name" not in captured.err


def test_track__async__project_name_propagated_to_trace_and_span(
    fake_backend,
):
    captured_project = None

    @tracker.track(project_name="async-project")
    async def f(x):
        nonlocal captured_project
        captured_project = context_storage.get_context_project_name()
        return "output"

    asyncio.run(f("input"))
    tracker.flush_tracker()

    assert captured_project == "async-project"
    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].project_name == "async-project"
    assert fake_backend.trace_trees[0].spans[0].project_name == "async-project"


def test_project_context_manager__sets_project_for_tracked_functions(
    fake_backend,
):
    @tracker.track
    def f(x):
        return "output"

    with context_storage.project_context("ctx-manager-project"):
        f("input")

    tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert_equal(
        TraceModel(
            id=ANY_BUT_NONE,
            name="f",
            input={"x": "input"},
            output={"output": "output"},
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            project_name="ctx-manager-project",
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="f",
                    input={"x": "input"},
                    output={"output": "output"},
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name="ctx-manager-project",
                    spans=[],
                )
            ],
        ),
        fake_backend.trace_trees[0],
    )


def test_project_context_manager__resets_after_exit(fake_backend):
    with context_storage.project_context("temp-project"):
        assert context_storage.get_context_project_name() == "temp-project"

    assert context_storage.get_context_project_name() is None


def test_project_context_manager__nested__outer_sticks(fake_backend):
    with context_storage.project_context("outer-project"):
        assert context_storage.get_context_project_name() == "outer-project"

        with context_storage.project_context("inner-project"):
            assert context_storage.get_context_project_name() == "outer-project"

        assert context_storage.get_context_project_name() == "outer-project"

    assert context_storage.get_context_project_name() is None


def test_project_context_manager__sticks_over_nested_decorator(
    fake_backend,
):
    captured_project = None

    @tracker.track(project_name="decorator-project")
    def f(x):
        nonlocal captured_project
        captured_project = context_storage.get_context_project_name()
        return "output"

    with context_storage.project_context("ctx-manager-project"):
        f("input")

    tracker.flush_tracker()

    assert captured_project == "ctx-manager-project"
    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].project_name == "ctx-manager-project"
    assert fake_backend.trace_trees[0].spans[0].project_name == "ctx-manager-project"


def test_resolve_project_name__reads_context_var(fake_backend):
    client = opik_client.get_client_cached()

    owner_id = "test-owner"
    context_storage.try_acquire_context_project_name("context-project", owner_id)
    try:
        resolved = client._resolve_project_name(None)
    finally:
        context_storage.release_context_project_name_if_owner(owner_id)

    assert resolved == "context-project"


def test_resolve_project_name__explicit_arg_wins_over_context_var(
    fake_backend,
):
    client = opik_client.get_client_cached()

    owner_id = "test-owner"
    context_storage.try_acquire_context_project_name("context-project", owner_id)
    try:
        resolved = client._resolve_project_name("explicit-project")
    finally:
        context_storage.release_context_project_name_if_owner(owner_id)

    assert resolved == "explicit-project"


def test_resolve_project_name__falls_back_to_client_default(fake_backend):
    client = opik_client.get_client_cached()
    resolved = client._resolve_project_name(None)
    assert resolved == client._project_name


def test_resolve_project_name__context_manager_sets_it(fake_backend):
    client = opik_client.get_client_cached()

    with context_storage.project_context("ctx-project"):
        resolved = client._resolve_project_name(None)

    assert resolved == "ctx-project"
