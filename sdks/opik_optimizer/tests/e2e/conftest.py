"""Global pytest configuration for e2e optimizer tests."""

import os

os.environ.setdefault("OPIK_OPTIMIZER_TOOL_CALL_MAX_ITERATIONS", "1")
