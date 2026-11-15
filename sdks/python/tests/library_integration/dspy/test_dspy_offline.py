"""
Offline checks that run against whichever dspy version is installed, without
LLM calls. dspy 3.5 replaced the LiteLLM call path with lm15 Requests, which
changed the LM history shape the callback reads usage from, so the stub LM is
built per version.
"""

import uuid

import dspy
import opik
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


def _lm_spans(trace_tree):
    # A top-level Predict becomes the trace itself, so LM spans are its direct children.
    return trace_tree.spans


def test_dspy_offline__adapter_call__lm_span_has_usage(fake_backend):
    opik_callback = OpikCallback()
    dspy.configure(lm=_stub_lm(cache=False), callbacks=[opik_callback])

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
    dspy.configure(lm=lm, callbacks=[opik_callback])

    @opik.track
    def call_lm():
        return lm("What is 2+2?")

    call_lm()
    opik_callback.flush()
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    lm_span = fake_backend.trace_trees[0].spans[0].spans[0]
    assert lm_span.usage == ANY_USAGE_DICT


def test_dspy_offline__cached_call__cache_hit_true_and_no_usage(fake_backend):
    opik_callback = OpikCallback()
    dspy.configure(lm=_stub_lm(cache=True), callbacks=[opik_callback])

    predict = dspy.Predict("question -> answer")
    question = f"What is {uuid.uuid4().hex}?"
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
