"""
Top-level pytest configuration for the opik_optimizer package.

Pytest requires `pytest_plugins` to be declared in a *top-level* conftest
to avoid surprising global side effects.
"""

PYTEST_PLUGIN_PREFIX = "sdks.opik_optimizer.tests.unit.fixtures"

pytest_plugins = [
    f"{PYTEST_PLUGIN_PREFIX}.display_fixtures",
    f"{PYTEST_PLUGIN_PREFIX}.llm_fixtures",
    f"{PYTEST_PLUGIN_PREFIX}.llm_sequence_fixtures",
    f"{PYTEST_PLUGIN_PREFIX}.opik_platform_fixtures",
    f"{PYTEST_PLUGIN_PREFIX}.throttle_fixtures",
    f"{PYTEST_PLUGIN_PREFIX}.token_fixtures",
    f"{PYTEST_PLUGIN_PREFIX}.prompt_fixtures",
    f"{PYTEST_PLUGIN_PREFIX}.common_data_fixtures",
    f"{PYTEST_PLUGIN_PREFIX}.prompt_library_fixtures",
    f"{PYTEST_PLUGIN_PREFIX}.agent_fixtures",
    f"{PYTEST_PLUGIN_PREFIX}.evaluation_fixtures",
    f"{PYTEST_PLUGIN_PREFIX}.optimization_flow_fixtures",
    f"{PYTEST_PLUGIN_PREFIX}.warnings_fixtures",
]
