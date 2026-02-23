from opik.api_objects.config.context import config_context, get_active_config_mask


class TestConfigContext:
    def test_no_context__returns_none(self):
        assert get_active_config_mask() is None

    def test_inside_context__returns_active_mask(self):
        with config_context("mask-1"):
            assert get_active_config_mask() == "mask-1"

    def test_after_context_exit__returns_none(self):
        with config_context("mask-1"):
            pass
        assert get_active_config_mask() is None

    def test_nested_contexts__inner_wins_then_restores_outer(self):
        with config_context("outer"):
            assert get_active_config_mask() == "outer"
            with config_context("inner"):
                assert get_active_config_mask() == "inner"
            assert get_active_config_mask() == "outer"
        assert get_active_config_mask() is None

    def test_exception_inside_context__mask_still_resets(self):
        try:
            with config_context("mask-err"):
                assert get_active_config_mask() == "mask-err"
                raise ValueError("test error")
        except ValueError:
            pass
        assert get_active_config_mask() is None
