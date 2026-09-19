from unittest import mock

import pytest

from opik.evaluation.models.langchain import langchain_chat_model, opik_monitoring


class FakeOpikTracer:
    """Stand-in for ``opik.integrations.langchain.OpikTracer``."""

    def __init__(self, *args, **kwargs):  # noqa: ANN002, ANN003
        pass


class FakeLangchainIntegrationModule:
    OpikTracer = FakeOpikTracer


@pytest.fixture
def stub_opik_tracer():
    # opik_monitoring imports OpikTracer lazily, so the stub only has to be present
    # in sys.modules for the duration of the call.
    with mock.patch.dict(
        "sys.modules",
        {"opik.integrations.langchain": FakeLangchainIntegrationModule()},
    ):
        yield


@pytest.fixture
def stub_message_conversion():
    # langchain_core is not a unit-test dependency, so the message conversion step is
    # orthogonal to what these tests pin and is stubbed out here.
    with mock.patch.object(
        langchain_chat_model.message_converters,
        "convert_to_langchain_messages",
        return_value=[],
    ):
        yield


def _build_model(track: bool) -> mock.MagicMock:
    """A model wired to a mock engine; returns the engine via ``tested._engine``."""
    engine = mock.MagicMock()
    engine.invoke.return_value = mock.MagicMock(content="ok")
    engine.ainvoke = mock.AsyncMock(return_value=mock.MagicMock(content="ok"))
    tested = langchain_chat_model.LangchainChatModel(
        chat_model=mock.MagicMock(), track=track
    )
    tested._engine = engine
    return tested


def _tracer_count(call_kwargs: dict) -> int:
    callbacks = (call_kwargs.get("config") or {}).get("callbacks") or []
    return len(
        [callback for callback in callbacks if isinstance(callback, FakeOpikTracer)]
    )


MESSAGES = [{"content": "hi", "role": "user"}]


def test_generate_provider_response__track_enabled__attaches_opik_tracer(
    stub_opik_tracer, stub_message_conversion
):
    tested = _build_model(track=True)

    tested.generate_provider_response(messages=MESSAGES)

    assert _tracer_count(tested._engine.invoke.call_args.kwargs) == 1


def test_generate_provider_response__track_disabled__does_not_attach_opik_tracer(
    stub_opik_tracer, stub_message_conversion
):
    tested = _build_model(track=False)

    tested.generate_provider_response(messages=MESSAGES)

    assert _tracer_count(tested._engine.invoke.call_args.kwargs) == 0


@pytest.mark.asyncio
async def test_agenerate_provider_response__track_disabled__does_not_attach_opik_tracer(
    stub_opik_tracer, stub_message_conversion
):
    tested = _build_model(track=False)

    await tested.agenerate_provider_response(messages=MESSAGES)

    assert _tracer_count(tested._engine.ainvoke.call_args.kwargs) == 0


def test_add_opik_tracer_to_params__still_attaches_when_called_directly(
    stub_opik_tracer,
):
    """Only the call sites become conditional; the helper keeps its contract."""
    result = opik_monitoring.add_opik_tracer_to_params({})

    assert isinstance(result["config"]["callbacks"][0], FakeOpikTracer)
