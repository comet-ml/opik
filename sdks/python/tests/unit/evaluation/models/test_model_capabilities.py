from opik.evaluation.models import ModelCapabilities


def test_supports_vision_defaults_to_false_without_name() -> None:
    assert ModelCapabilities.supports_vision(None) is False


def test_custom_capability_registration() -> None:
    ModelCapabilities.register_capability_detector(
        "custom", lambda model: model.startswith("custom-")
    )

    assert ModelCapabilities.supports("custom", "custom-model") is True
    assert ModelCapabilities.supports("custom", "text-model") is False


def test_supports_vision_handles_provider_prefix() -> None:
    assert ModelCapabilities.supports_vision("anthropic/claude-3-opus") is True
