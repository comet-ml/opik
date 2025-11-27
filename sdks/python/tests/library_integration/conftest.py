import opik
import pytest

from ..testlib import environment


@pytest.fixture(autouse=True)
def reset_tracing_to_config_default():
    opik.reset_tracing_to_config_default()
    yield
    opik.reset_tracing_to_config_default()


# Define a condition to skip the entire package
def should_skip_package():
    return not environment.has_openai_api_key()


@pytest.fixture(scope="session", autouse=True)
def skip_all_tests_in_package(request):
    if should_skip_package():
        pytest.skip(
            "Skipping entire 'library integration tests' because authorization credentials not found",
            allow_module_level=True,
        )
