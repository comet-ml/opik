GEPA prompt control

GEPA lives inside DSPy and does not expose the same per-template prompt hooks that
our other optimizers use. The Opik optimizer wrapper therefore does not provide
prompt override/factory wiring for GEPA.

If you need prompt customization with GEPA:
- Use DSPy-native configuration for prompts/teleprompts where applicable.
- Treat the optimizer inputs (prompt, dataset, metric) as the integration surface
  for customization, rather than internal prompt templates.

This file documents the intentional gap so callers do not expect prompt overrides
to be available on the GEPA optimizer in this SDK.
