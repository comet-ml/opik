import opik.integrations.openai.openai_videos_decorator as videos_decorator


def test_estimate_video_cost_for_sora() -> None:
    result = {"model": "openai/sora-2", "seconds": "4"}
    assert videos_decorator._build_video_usage(result) == {"video_duration_seconds": 4}


def test_estimate_video_cost_unknown_model() -> None:
    result = {"model": "unknown-model", "duration": None}
    assert videos_decorator._build_video_usage(result) is None
