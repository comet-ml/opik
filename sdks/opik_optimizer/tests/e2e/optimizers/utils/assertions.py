"""
Assertion helpers for optimizer e2e tests.

This module provides functions to verify:
- Tools and function_map preservation
- Prompt content changes
- Multimodal structure preservation
"""

from opik_optimizer.api_objects.chat_prompt import ChatPrompt


def assert_tools_preserved(
    original_prompt: ChatPrompt,
    optimized_prompt: ChatPrompt,
) -> None:
    """
    Assert that tools and function_map are preserved after optimization.

    Args:
        original_prompt: The original ChatPrompt before optimization
        optimized_prompt: The optimized ChatPrompt after optimization

    Raises:
        AssertionError: If tools or function_map were modified
    """
    # Check tools are identical
    assert optimized_prompt.tools == original_prompt.tools, (
        f"Tools were modified during optimization.\n"
        f"Original: {original_prompt.tools}\n"
        f"Optimized: {optimized_prompt.tools}"
    )

    # Check function_map keys are identical
    original_keys = set(original_prompt.function_map.keys())
    optimized_keys = set(optimized_prompt.function_map.keys())
    assert optimized_keys == original_keys, (
        f"Function map keys were modified during optimization.\n"
        f"Original: {original_keys}\n"
        f"Optimized: {optimized_keys}"
    )


def assert_prompt_changed(
    original_prompt: ChatPrompt,
    optimized_prompt: ChatPrompt,
) -> bool:
    """
    Check if at least one message content changed during optimization.

    Args:
        original_prompt: The original ChatPrompt before optimization
        optimized_prompt: The optimized ChatPrompt after optimization

    Returns:
        True if any content changed, False otherwise

    Note:
        This function does not raise an assertion error because optimization
        might not always change the prompt if it's already optimal.
    """
    original_msgs = original_prompt.get_messages()
    optimized_msgs = optimized_prompt.get_messages()

    # Check that at least one message content differs
    for orig, opt in zip(original_msgs, optimized_msgs):
        if orig.get("content") != opt.get("content"):
            return True

    return False


def assert_multimodal_structure_preserved(
    original_prompt: ChatPrompt,
    optimized_prompt: ChatPrompt,
) -> None:
    """
    Assert that multimodal content structure is preserved after optimization.

    Args:
        original_prompt: The original ChatPrompt with multimodal content
        optimized_prompt: The optimized ChatPrompt

    Raises:
        AssertionError: If multimodal structure was not preserved
    """
    original_msgs = original_prompt.get_messages()
    optimized_msgs = optimized_prompt.get_messages()

    for i, (orig, opt) in enumerate(zip(original_msgs, optimized_msgs)):
        orig_content = orig.get("content")
        opt_content = opt.get("content")

        # If original had list content (multimodal), optimized should too
        if isinstance(orig_content, list):
            assert isinstance(opt_content, list), (
                f"Message {i}: Multimodal structure was lost. "
                f"Original had list content, optimized has {type(opt_content)}"
            )

            # Check that content types are preserved
            orig_types = [
                part.get("type") for part in orig_content if isinstance(part, dict)
            ]
            opt_types = [
                part.get("type") for part in opt_content if isinstance(part, dict)
            ]
            assert orig_types == opt_types, (
                f"Message {i}: Content part types changed. "
                f"Original: {orig_types}, Optimized: {opt_types}"
            )

            # Check that image_url parts are preserved
            for orig_part, opt_part in zip(orig_content, opt_content):
                if isinstance(orig_part, dict) and orig_part.get("type") == "image_url":
                    assert opt_part.get("type") == "image_url", (
                        f"Image URL part was converted to {opt_part.get('type')}"
                    )
                    # The URL placeholder should be preserved
                    orig_url = orig_part.get("image_url", {}).get("url", "")
                    opt_url = opt_part.get("image_url", {}).get("url", "")
                    if "{" in orig_url and "}" in orig_url:
                        # It's a template placeholder, should be preserved
                        assert "{" in opt_url and "}" in opt_url, (
                            f"Image URL placeholder was not preserved. "
                            f"Original: {orig_url}, Optimized: {opt_url}"
                        )


def assert_multi_prompt_changed(
    original_prompts: dict[str, ChatPrompt],
    optimized_prompts: dict[str, ChatPrompt],
) -> dict[str, bool]:
    """
    Check and report which prompts changed during multi-prompt optimization.

    Args:
        original_prompts: Dict of original ChatPrompts
        optimized_prompts: Dict of optimized ChatPrompts

    Returns:
        Dict mapping prompt names to whether they changed
    """
    changes = {}
    for name in original_prompts:
        if name in optimized_prompts:
            changes[name] = assert_prompt_changed(
                original_prompts[name],
                optimized_prompts[name],
            )
        else:
            changes[name] = False
    return changes
