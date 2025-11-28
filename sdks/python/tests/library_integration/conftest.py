import opik
import pytest

from ..testlib import environment


@pytest.fixture(autouse=True)
def reset_tracing_to_config_default():
    opik.reset_tracing_to_config_default()
    yield
    opik.reset_tracing_to_config_default()


@pytest.fixture(scope="session", autouse=True)
def skip_all_tests_in_package(request):
    if environment.should_skip_library_integration_tests():
        pytest.skip(
            "Skipping entire 'library integration tests' because authorization credentials not found",
            allow_module_level=True,
        )
