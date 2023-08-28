import pytest
from testix import *

from comet_llm.autologgers.openai import context, hooks


@pytest.fixture(autouse=True)
def mock_imports(patch_module):
    patch_module(hooks, "span")
    patch_module(hooks, "chain")
    patch_module(hooks, "chains_state")
    patch_module(hooks, "chains_api")
    patch_module(hooks, "config")
    patch_module(hooks, "chat_completion_parsers")
    patch_module(hooks, "experiment_info")
    patch_module(context, "CONTEXT", context.OpenAIContext())


def test_before_chat_completion_create__stream_is_True__nothing_done():
    NOT_USED = None
    KWARGS = {"some-key": "some-value"}

    with Scenario() as s:
        s.config.enabled() >> True
        s.chat_completion_parsers.create_arguments_supported(KWARGS) >> False

        hooks.before_chat_completion_create(
            NOT_USED,
            **KWARGS
        )

        assert context.CONTEXT.chain is None
        assert context.CONTEXT.span is None



def test_before_chat_completion_create__global_chain_exists__span_attached_to_global_chain():
    NOT_USED = None
    KWARGS = {"some-key": "some-value"}

    span_instance = Fake("span_instance")
    with Scenario() as s:
        s.config.enabled() >> True
        s.chat_completion_parsers.create_arguments_supported(KWARGS) >> True
        s.chat_completion_parsers.parse_create_arguments(KWARGS) >> ("the-inputs", "the-metadata")
        s.chains_state.global_chain_exists() >> True
        s.chains_state.get_global_chain() >> "global-chain"
        s.span.Span(
            inputs="the-inputs",
            metadata = "the-metadata",
            chain="global-chain",
            category="llm",
        ) >> span_instance
        s.span_instance.__api__start__()

        hooks.before_chat_completion_create(
            NOT_USED,
            **KWARGS
        )

        assert context.CONTEXT.chain is None
        assert context.CONTEXT.span is span_instance


def test_before_chat_completion_create__global_chain_does_not_exist__session_chain_created__span_attached_to_session_chain():
    NOT_USED = None
    KWARGS = {"some-key": "some-value"}


    span_instance = Fake("span_instance")
    with Scenario() as s:
        s.config.enabled() >> True
        s.chat_completion_parsers.create_arguments_supported(KWARGS) >> True
        s.chat_completion_parsers.parse_create_arguments(KWARGS) >> ("the-inputs", "the-metadata")
        s.chains_state.global_chain_exists() >> False
        s.experiment_info.get() >> "experiment-info"
        s.chain.Chain(
            inputs="the-inputs",
            metadata = "the-metadata",
            experiment_info = "experiment-info"
        ) >> "session-chain"
        s.span.Span(
            inputs="the-inputs",
            metadata = "the-metadata",
            chain="session-chain",
            category="llm",
        ) >> span_instance
        s.span_instance.__api__start__()

        hooks.before_chat_completion_create(
            NOT_USED,
            **KWARGS
        )
        assert context.CONTEXT.chain is "session-chain"
        assert context.CONTEXT.span is span_instance


def test_before_chat_completion_create__autologging_disabled__nothing_done():
    NOT_USED = None
    with Scenario() as s:
        s.config.enabled() >> False
        hooks.before_chat_completion_create(NOT_USED)


def test_after_chat_completion_create__session_chain_exists__session_chain_used_and_ended():
    NOT_USED = None

    context.CONTEXT.chain = Fake("chain_instance")
    context.CONTEXT.span = Fake("span_instance")

    with Scenario() as s:
        s.config.enabled() >> True
        s.chat_completion_parsers.parse_create_result("return-value") >> ("the-outputs", "the-metadata")
        s.span_instance.set_outputs(
            outputs="the-outputs",
            metadata="the-metadata"
        )
        s.span_instance.__api__end__()
        s.chain_instance.set_outputs(
            outputs="the-outputs",
            metadata="the-metadata"
        )
        s.chains_api.log_chain(context.CONTEXT.chain)

        hooks.after_chat_completion_create(NOT_USED, "return-value")

        assert context.CONTEXT.chain is None
        assert context.CONTEXT.span is None


def test_after_chat_completion_create__autologging_disabled__nothing_done_but_context_cleared():
    NOT_USED = None
    context.CONTEXT.chain = "the-chain"
    context.CONTEXT.span = "the-span"
    with Scenario() as s:
        s.config.enabled() >> False

        hooks.after_chat_completion_create(NOT_USED, NOT_USED)

        assert context.CONTEXT.chain is None
        assert context.CONTEXT.span is None


def test_after_chat_completion_create__context_span_is_None__nothing_done():
    NOT_USED = None
    context.CONTEXT.span = None
    with Scenario() as s:
        s.config.enabled() >> True

        hooks.after_chat_completion_create(NOT_USED, NOT_USED)

        assert context.CONTEXT.chain is None
        assert context.CONTEXT.span is None