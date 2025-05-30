import opik
from ...testlib import TraceModel, SpanModel, ANY_BUT_NONE, assert_equal


def test_span__provider_supported__usage_format_is_correct__usage_converted_to_opik_format(
    fake_backend,
):
    opik_client = opik.Opik(_use_batching=True)

    opik_client.span(
        type="llm",
        name="some-name",
        usage={
            "completion_tokens": 10,
            "prompt_tokens": 20,
            "total_tokens": 30,
        },
        provider="openai",
    )
    opik_client.end()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        name="some-name",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                type="llm",
                name="some-name",
                usage={
                    "completion_tokens": 10,
                    "prompt_tokens": 20,
                    "total_tokens": 30,
                    "original_usage.completion_tokens": 10,
                    "original_usage.prompt_tokens": 20,
                    "original_usage.total_tokens": 30,
                },
                spans=[],
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


def test_span__provider_not_passed__usage_format_is_correct_for_some_provider__usage_converted_to_opik_format(
    fake_backend,
):
    opik_client = opik.Opik(_use_batching=True)

    opik_client.span(
        type="llm",
        name="some-name",
        usage={  # Anthropic format
            "input_tokens": 10,
            "output_tokens": 20,
        },
    )
    opik_client.end()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        name="some-name",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                type="llm",
                name="some-name",
                usage={
                    "completion_tokens": 20,
                    "prompt_tokens": 10,
                    "total_tokens": 30,
                    "original_usage.input_tokens": 10,
                    "original_usage.output_tokens": 20,
                },
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


def test_span__unknown_provider_passed__usage_format_is_correct_for_some_provider__usage_converted_to_opik_format(
    fake_backend,
):
    opik_client = opik.Opik(_use_batching=True)

    opik_client.span(
        type="llm",
        name="some-name",
        usage={  # Anthropic format
            "input_tokens": 10,
            "output_tokens": 20,
        },
        provider="my-llm-provider",
    )
    opik_client.end()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        name="some-name",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                type="llm",
                name="some-name",
                usage={
                    "completion_tokens": 20,
                    "prompt_tokens": 10,
                    "total_tokens": 30,
                    "original_usage.input_tokens": 10,
                    "original_usage.output_tokens": 20,
                },
                provider="my-llm-provider",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


def test_span__unknown_provider_passed__usage_format_is_also_unknown__usage_flattened__prefix_added_to_keys__only_int_values_are_kept(
    fake_backend,
):
    opik_client = opik.Opik(_use_batching=True)

    opik_client.span(
        type="llm",
        name="some-name",
        usage={
            "abc_input_tokens": 10,
            "abc_output_tokens": 20,
            "abc_nested_dict": {
                "nested_int": 10,
                "nested_str": "abc",
            },
        },
        provider="my-llm-provider",
    )
    opik_client.end()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        name="some-name",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                type="llm",
                name="some-name",
                usage={
                    "original_usage.abc_input_tokens": 10,
                    "original_usage.abc_output_tokens": 20,
                    "original_usage.abc_nested_dict.nested_int": 10,
                },
                provider="my-llm-provider",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


def test_span__user_added_openai_keys_to_unknown_usage_themselves__they_are_included_to_usage_dict_without_prefix(
    fake_backend,
):
    opik_client = opik.Opik(_use_batching=True)

    opik_client.span(
        type="llm",
        name="some-name",
        usage={
            "prompt_tokens": 10,
            "completion_tokens": 20,
            "total_tokens": 30,
            "abc_input_tokens": 10,
            "abc_output_tokens": 20,
        },
        provider="my-llm-provider",
    )
    opik_client.end()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        name="some-name",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                type="llm",
                name="some-name",
                usage={
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30,
                    "original_usage.prompt_tokens": 10,
                    "original_usage.completion_tokens": 20,
                    "original_usage.total_tokens": 30,
                    "original_usage.abc_input_tokens": 10,
                    "original_usage.abc_output_tokens": 20,
                },
                provider="my-llm-provider",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
