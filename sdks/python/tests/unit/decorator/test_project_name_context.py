import asyncio

from opik import context_storage
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


def test_track__nested_functions_without_project__child_inherits_parent_project(
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


def test_track__nested_decorator_with_different_project__outer_project_preserved_and_warning_emitted(
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


def test_track__nested_decorator_with_same_project__no_warning_emitted(
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


def test_track__async_with_project_name__trace_and_span_use_that_project(
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


def test_project_context__tracked_function_inside__trace_uses_context_project(
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


def test_project_context__after_exit__context_cleared(fake_backend):
    with context_storage.project_context("temp-project"):
        assert context_storage.get_context_project_name() == "temp-project"

    assert context_storage.get_context_project_name() is None


def test_project_context__nested_with_different_name__outer_preserved(fake_backend):
    with context_storage.project_context("outer-project"):
        assert context_storage.get_context_project_name() == "outer-project"

        with context_storage.project_context("inner-project"):
            assert context_storage.get_context_project_name() == "outer-project"

        assert context_storage.get_context_project_name() == "outer-project"

    assert context_storage.get_context_project_name() is None


def test_project_context__decorator_with_different_project_inside__outer_project_preserved(
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


def test_track__context_var_set_externally__trace_uses_context_project(
    fake_backend,
):
    @tracker.track
    def f(x):
        return "output"

    with context_storage.project_context("context-project"):
        f("input")

    tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].project_name == "context-project"
    assert fake_backend.trace_trees[0].spans[0].project_name == "context-project"


def test_track__explicit_project_name_arg__takes_precedence_over_no_context(
    fake_backend,
):
    @tracker.track(project_name="explicit-project")
    def f(x):
        return "output"

    f("input")
    tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].project_name == "explicit-project"


def test_track__no_project_name_no_context__falls_back_to_client_default(
    fake_backend,
):
    @tracker.track
    def f(x):
        return "output"

    f("input")
    tracker.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]
    assert trace_tree.project_name is not None


def test_project_context__resolve_project_name_for_non_trace_operations__returns_context_value(
    fake_backend,
):
    @tracker.track
    def f(x):
        return "output"

    with context_storage.project_context("ctx-project"):
        f("input")

    tracker.flush_tracker()

    assert fake_backend.trace_trees[0].project_name == "ctx-project"
