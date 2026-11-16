"""
Offline checks that run against whichever dspy version is installed, without
LLM calls. dspy 3.5 replaced the LiteLLM call path with lm15 Requests, which
changed the LM history shape the callback reads usage from, so the stub LM is
built per version.
"""

import uuid

import dspy
import opik
import pytest
from packaging.version import Version

from opik.integrations.dspy.callback import OpikCallback
from ...testlib import ANY_BUT_NONE, ANY_DICT

ANY_USAGE_DICT = ANY_DICT.containing(
    {
        "completion_tokens": ANY_BUT_NONE,
        "prompt_tokens": ANY_BUT_NONE,
        "total_tokens": ANY_BUT_NONE,
    }
)


def _stub_lm(cache: bool) -> dspy.LM:
    if Version(dspy.__version__) >= Version("3.5.0"):
        from dspy import lm15

        class StubEngine:
            def complete(self, request):
                return lm15.Response(
                    id="resp_stub",
                    model="gpt-5-nano",
                    message=lm15.Message.assistant("<answer>\n4\n</answer>"),
                    finish_reason="stop",
                    usage=lm15.Usage(input_tokens=11, output_tokens=7, total_tokens=18),
                )

        return dspy.LM("openai/gpt-5-nano", engine=StubEngine(), cache=cache)

    return dspy.LM(
        "openai/gpt-5-nano",
        mock_response="[[ ## answer ## ]]\n4\n\n[[ ## completed ## ]]",
        cache=cache,
    )


@pytest.mark.skipif(
    Version(dspy.__version__) < Version("3.5.0"),
    reason="dspy<3.5 exposes the routed provider on the LiteLLM response instead",
)
def test_dspy_offline__openrouter_reply__span_has_routed_provider_and_billed_cost(
    fake_backend,
):
    from dspy import lm15

    class OpenRouterStubEngine:
        def complete(self, request):
            return lm15.Response(
                id="gen_stub",
                model="qwen/qwen3-8b",
                message=lm15.Message.assistant("<answer>\n4\n</answer>"),
                finish_reason="stop",
                usage=lm15.Usage(input_tokens=11, output_tokens=7, total_tokens=18),
                provider_data={
                    "provider": "Novita",
                    "usage": {
                        "prompt_tokens": 11,
                        "completion_tokens": 7,
                        "cost": 9.5e-06,
                    },
                },
            )

    lm = dspy.LM("openrouter/qwen/qwen3-8b", engine=OpenRouterStubEngine(), cache=False)
    opik_callback = OpikCallback()
    with dspy.context(lm=lm, callbacks=[opik_callback]):
        dspy.Predict("question -> answer")(question="What is 2+2?")
    opik_callback.flush()

    lm_span = _lm_spans(fake_backend.trace_trees[0])[0]
    assert lm_span.provider == "novita"
    assert lm_span.metadata["llm_router"] == "openrouter"
    assert lm_span.total_cost == 9.5e-06


def _lm_spans(trace_tree):
    # A top-level Predict becomes the trace itself, so LM spans are its direct children.
    return trace_tree.spans


def test_dspy_offline__adapter_call__lm_span_has_usage(fake_backend):
    opik_callback = OpikCallback()
    with dspy.context(lm=_stub_lm(cache=False), callbacks=[opik_callback]):
        dspy.Predict("question -> answer")(question="What is 2+2?")
    opik_callback.flush()

    assert len(fake_backend.trace_trees) == 1
    lm_spans = _lm_spans(fake_backend.trace_trees[0])
    assert len(lm_spans) == 1
    assert lm_spans[0].usage == ANY_USAGE_DICT
    assert lm_spans[0].metadata["cache_hit"] is False


def test_dspy_offline__string_prompt_call__lm_span_has_usage(fake_backend):
    lm = _stub_lm(cache=False)
    opik_callback = OpikCallback()

    @opik.track
    def call_lm():
        return lm("What is 2+2?")

    with dspy.context(lm=lm, callbacks=[opik_callback]):
        call_lm()
    opik_callback.flush()
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    lm_span = fake_backend.trace_trees[0].spans[0].spans[0]
    assert lm_span.usage == ANY_USAGE_DICT


def test_dspy_offline__cached_call__cache_hit_true_and_no_usage(fake_backend):
    opik_callback = OpikCallback()
    predict = dspy.Predict("question -> answer")
    question = f"What is {uuid.uuid4().hex}?"
    with dspy.context(lm=_stub_lm(cache=True), callbacks=[opik_callback]):
        predict(question=question)
        predict(question=question)
    opik_callback.flush()

    assert len(fake_backend.trace_trees) == 2
    first_lm_span = _lm_spans(fake_backend.trace_trees[0])[0]
    cached_lm_span = _lm_spans(fake_backend.trace_trees[1])[0]

    assert first_lm_span.usage == ANY_USAGE_DICT
    assert first_lm_span.metadata["cache_hit"] is False
    assert cached_lm_span.usage is None
    assert cached_lm_span.metadata["cache_hit"] is True
