"""Global pytest configuration for the opik_optimizer test suite."""

import os

# LiteLLM spawns a background logging worker that binds to the current event loop.
# When pytest tears down the loop between tests (especially with pytest-asyncio/xdist),
# the worker can try to use a queue bound to the old loop, resulting in noisy
# "Queue is bound to a different event loop" errors. Disabling queue logging keeps
# the tests quiet without affecting functionality.
os.environ.setdefault("LITELLM_USE_QUEUE_LOGGING", "false")

