import opik
import pytest
from opik.rest_api.core import api_error


def test_auth_check__happyflow(opik_client: opik.Opik):
    # Assuming opik client is correctly configured for tests, no
    # exceptions must be raised.
    assert opik_client.auth_check() is None


def test_auth_check__not_existing_workspace__api_error_raised():
    opik_client = opik.Opik(
        workspace="workspace-that-does-not-exist-in-any-installation"
    )
    with pytest.raises(api_error.ApiError):
        opik_client.auth_check()
