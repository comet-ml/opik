import importlib
import sys
import types
from types import SimpleNamespace

import pytest

from opik.api_objects.span import SpanData

pytestmark = pytest.mark.usefixtures("ensure_openai_configured")


def _install_litellm_stub(monkeypatch: pytest.MonkeyPatch) -> None:
    litellm_mod = types.ModuleType("litellm")
    types_mod = types.ModuleType("litellm.types")
    utils_mod = types.ModuleType("litellm.types.utils")
    utils_mod.ModelResponse = type("ModelResponse", (), {})
    types_mod.utils = utils_mod
    litellm_mod.types = types_mod

    core_utils_mod = types.ModuleType("litellm.litellm_core_utils")
    streaming_mod = types.ModuleType("litellm.litellm_core_utils.streaming_handler")
    streaming_mod.CustomStreamWrapper = type("CustomStreamWrapper", (), {})
    core_utils_mod.streaming_handler = streaming_mod
    litellm_mod.litellm_core_utils = core_utils_mod

    litellm_mod.completion_cost = lambda completion_response: None

    monkeypatch.setitem(sys.modules, "litellm", litellm_mod)
    monkeypatch.setitem(sys.modules, "litellm.types", types_mod)
    monkeypatch.setitem(sys.modules, "litellm.types.utils", utils_mod)
    monkeypatch.setitem(sys.modules, "litellm.litellm_core_utils", core_utils_mod)
    monkeypatch.setitem(
        sys.modules, "litellm.litellm_core_utils.streaming_handler", streaming_mod
    )


def test_litellm_completion_decorator_accepts_object_output(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _install_litellm_stub(monkeypatch)

    sys.modules.pop("opik.integrations.litellm.litellm_completion_decorator", None)
    lcd = importlib.import_module(
        "opik.integrations.litellm.litellm_completion_decorator"
    )

    decorator = lcd.LiteLLMCompletionTrackDecorator()
    span_data = SpanData(trace_id="trace-id", project_name="proj", name="span")
    output = SimpleNamespace(model="gpt-4o-mini", usage=None, choices=[])

    result = decorator._end_span_inputs_preprocessor(
        output=output,
        capture_output=True,
        current_span_data=span_data,
    )

    assert result.output is not None
    assert result.model == "gpt-4o-mini"
