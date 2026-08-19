import inspect

from opik.evaluation.threads import evaluator


def test_evaluate_threads__legacy_positional_call__binds_the_original_parameters():
    """`trace_context_transform` must not shift the pre-existing positional arguments.

    Callers written before it existed pass `verbose` (and the two arguments after it)
    positionally. If the new parameter is declared before them, `verbose=0` silently
    binds to the transform instead - it is falsy, so the context transform is skipped
    without error and verbose reverts to its default.
    """
    signature = inspect.signature(evaluator.evaluate_threads)

    bound = signature.bind(
        "project",
        None,
        None,
        [],
        lambda trace_input: trace_input,
        lambda trace_output: trace_output,
        0,
        4,
        500,
    )

    assert bound.arguments["verbose"] == 0
    assert bound.arguments["num_workers"] == 4
    assert bound.arguments["max_traces_per_thread"] == 500
    assert "trace_context_transform" not in bound.arguments


def test_evaluate_threads__trace_context_transform__is_keyword_only():
    parameter = inspect.signature(evaluator.evaluate_threads).parameters[
        "trace_context_transform"
    ]

    assert parameter.kind is inspect.Parameter.KEYWORD_ONLY
    assert parameter.default is None
