"""
Top-level pytest configuration for the opik_optimizer package.

Pytest requires `pytest_plugins` to be declared in a *top-level* conftest
to avoid surprising global side effects.
"""

pytest_plugins = [
    "tests.unit.fixtures.display_fixtures",
    "tests.unit.fixtures.llm_fixtures",
    "tests.unit.fixtures.llm_sequence_fixtures",
    "tests.unit.fixtures.opik_platform_fixtures",
    "tests.unit.fixtures.throttle_fixtures",
    "tests.unit.fixtures.token_fixtures",
    "tests.unit.fixtures.prompt_fixtures",
    "tests.unit.fixtures.common_data_fixtures",
    "tests.unit.fixtures.prompt_library_fixtures",
    "tests.unit.fixtures.agent_fixtures",
    "tests.unit.fixtures.evaluation_fixtures",
    "tests.unit.fixtures.optimization_flow_fixtures",
    "tests.unit.fixtures.warnings_fixtures",
]
