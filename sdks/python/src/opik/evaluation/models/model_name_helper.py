"""Shared model-name predicates used by `models_factory` and adapters.

Lives in its own module so both `models_factory` (which routes a model
name to either the native Anthropic adapter or the LiteLLM adapter) and
`litellm_chat_model` (which needs to know it's talking to Anthropic so
it can apply provider-specific conflict resolution) can share a single
source of truth without creating an import cycle.

Adapters importing `models_factory` directly would cycle, because the
factory imports the adapter modules to construct instances; pulling the
predicate down into this leaf module breaks that cycle and keeps the
two call sites in lockstep.
"""


def is_anthropic_model(model_name: str) -> bool:
    """Whether the configured model name resolves to Anthropic.

    Matches LiteLLM's `anthropic/<name>` prefix convention plus the
    bare `claude-...` form that the Anthropic SDK accepts directly.
    Keep this in sync with the keys LiteLLM routes to its Anthropic
    provider — if LiteLLM adds new prefixes (or we accept a third-
    party Anthropic gateway), extend the check here and the change
    propagates everywhere.
    """
    return model_name.startswith("anthropic/") or model_name.startswith("claude")
