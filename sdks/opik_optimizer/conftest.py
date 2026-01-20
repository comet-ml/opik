"""
Top-level pytest configuration for the opik_optimizer package.

Pytest requires `pytest_plugins` to be declared in a *top-level* conftest
to avoid surprising global side effects.
"""

pytest_plugins = [
    "tests.unit.fixtures.llm_fixtures",
    "tests.unit.fixtures.llm_sequence_fixtures",
    "tests.unit.fixtures.opik_platform_fixtures",
    "tests.unit.fixtures.warnings_fixtures",
]

