from opik.api_objects.prompt.mask_context import (
    get_active_prompt_masks,
    get_mask_for_prompt,
    prompt_mask_context,
)


class TestPromptMaskContext:
    def test_no_context__returns_none(self):
        assert get_active_prompt_masks() is None

    def test_inside_context__returns_masks_dict(self):
        masks = {"prompt-1": "mask-a", "prompt-2": "mask-b"}
        with prompt_mask_context(masks):
            assert get_active_prompt_masks() == masks

    def test_get_mask_for_prompt__prompt_in_dict__returns_mask_id(self):
        masks = {"prompt-uuid": "mask-uuid"}
        with prompt_mask_context(masks):
            assert get_mask_for_prompt("prompt-uuid") == "mask-uuid"

    def test_get_mask_for_prompt__prompt_not_in_dict__returns_none(self):
        masks = {"other-prompt": "mask-abc"}
        with prompt_mask_context(masks):
            assert get_mask_for_prompt("unknown-prompt") is None

    def test_get_mask_for_prompt__no_context__returns_none(self):
        assert get_mask_for_prompt("any-prompt-id") is None

    def test_after_exit__resets_to_none(self):
        with prompt_mask_context({"prompt-1": "mask-1"}):
            pass
        assert get_active_prompt_masks() is None

    def test_context_with_none_masks__returns_none(self):
        with prompt_mask_context(None):
            assert get_active_prompt_masks() is None
            assert get_mask_for_prompt("any-id") is None

    def test_exception_inside__resets(self):
        try:
            with prompt_mask_context({"p": "m"}):
                assert get_active_prompt_masks() == {"p": "m"}
                raise ValueError("boom")
        except ValueError:
            pass
        assert get_active_prompt_masks() is None

    def test_nested_contexts__inner_wins_then_restores_outer(self):
        outer = {"p1": "m1"}
        inner = {"p2": "m2"}
        with prompt_mask_context(outer):
            assert get_active_prompt_masks() == outer
            with prompt_mask_context(inner):
                assert get_active_prompt_masks() == inner
                assert get_mask_for_prompt("p2") == "m2"
                assert get_mask_for_prompt("p1") is None
            assert get_active_prompt_masks() == outer
        assert get_active_prompt_masks() is None
