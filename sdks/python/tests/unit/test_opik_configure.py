from opik import configure
import pytest

from opik.exceptions import ConfigurationError


@pytest.mark.skip
@pytest.mark.parametrize(
    "api_key, url, workspace, local, should_raise",
    [
        (
            None,
            "http://example.com",
            "workspace1",
            True,
            False,
        ),  # Missing api_key, local=True
        (
            None,
            "http://example.com",
            "workspace1",
            False,
            True,
        ),  # Missing api_key, local=False
        ("apikey123", None, "workspace1", True, True),  # Missing url, local=True
        ("apikey123", None, "workspace1", False, True),  # Missing url, local=False
        (
            "apikey123",
            "http://example.com",
            None,
            True,
            True,
        ),  # Missing workspace, local=True
        (
            "apikey123",
            "http://example.com",
            None,
            False,
            True,
        ),  # Missing workspace, local=False
        (None, None, "workspace1", True, True),  # Missing api_key and url, local=True
        (None, None, "workspace1", False, True),  # Missing api_key and url, local=False
        (
            None,
            "http://example.com",
            None,
            True,
            True,
        ),  # Missing api_key and workspace, local=True
        (
            None,
            "http://example.com",
            None,
            False,
            True,
        ),  # Missing api_key and workspace, local=False
        ("apikey123", None, None, True, True),  # Missing url and workspace, local=True
        (
            "apikey123",
            None,
            None,
            False,
            True,
        ),  # Missing url and workspace, local=False
        (None, None, None, True, True),  # All missing, local=True
        (None, None, None, False, True),  # All missing, local=False
        (
            "apikey123",
            "http://example.com",
            "workspace1",
            True,
            False,
        ),  # All present, local=True
        (
            "apikey123",
            "http://example.com",
            "workspace1",
            False,
            False,
        ),  # All present, local=False
    ],
)
def test_login__force_new_settings__fail(api_key, url, workspace, local, should_raise):
    if should_raise:
        with pytest.raises(ConfigurationError):
            configure(
                api_key=api_key,
                url=url,
                workspace=workspace,
                force=True,
                use_local=local,
            )
    else:
        # No exception should be raised
        configure(
            api_key=api_key, url=url, workspace=workspace, force=True, use_local=local
        )
