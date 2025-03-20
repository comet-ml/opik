import pytest

@pytest.fixture(scope="session")
def browser():
    return None

@pytest.fixture(scope="session")
def browser_context():
    return None

@pytest.fixture(scope="session")
def client():
    return None

@pytest.fixture(scope="session")
def video_dir():
    return None

