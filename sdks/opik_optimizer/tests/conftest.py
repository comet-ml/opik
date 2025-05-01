import pytest
import sys
import os

# Add the src directory to the Python path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

@pytest.fixture(autouse=True)
def setup_test_environment():
    """Setup test environment before each test."""
    # Add any common setup code here
    yield
    # Add any common teardown code here 