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


def test_supports_vision_detects_common_suffixes() -> None:
    assert ModelCapabilities.supports_vision("provider/new-model-vision") is True
    assert ModelCapabilities.supports_vision("provider/new-model-vl") is True
    assert ModelCapabilities.supports_vision("gpt-4.1") is True
    assert ModelCapabilities.supports_vision("gpt-4.1-mini") is True


def test_supports_video_detects_keywords() -> None:
    assert ModelCapabilities.supports_video("provider/some-video-model") is True
    assert ModelCapabilities.supports_video("qwen/qwen2.5-vl-32b-instruct") is True
    assert ModelCapabilities.supports_video("text-only-model") is False
