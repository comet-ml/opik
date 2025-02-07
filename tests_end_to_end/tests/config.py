from dataclasses import dataclass
from typing import Optional
import os


@dataclass
class EnvConfig:
    base_url: str
    workspace: Optional[str] = None
    project_name: Optional[str] = "automated_tests_project"
    api_key: Optional[str] = None
    test_user_email: Optional[str] = None
    test_user_name: Optional[str] = None
    test_user_password: Optional[str] = None

    @property
    def api_url(self) -> str:
        """Get the API URL by appending /api to the base URL"""
        return f"{self.base_url}/api"

    @property
    def web_url(self) -> str:
        """Get the web URL (same as base URL)"""
        return self.base_url


def get_environment_config() -> EnvConfig:
    """
    Get configuration from environment variables.
    Uses default values for local development if environment variables are not set.
    """
    # Default to localhost if no base URL is provided
    base_url = os.getenv("OPIK_BASE_URL", "http://localhost:5173")

    config = EnvConfig(
        base_url=base_url,
        workspace=os.getenv("OPIK_TEST_USER_NAME", "default"),
        project_name=os.getenv("OPIK_TEST_PROJECT_NAME", "automated_tests_project"),
        test_user_email=os.getenv("OPIK_TEST_USER_EMAIL"),
        test_user_name=os.getenv("OPIK_TEST_USER_NAME"),
        test_user_password=os.getenv("OPIK_TEST_USER_PASSWORD"),
        api_key=os.getenv("OPIK_API_KEY"),
    )

    # For non-local environments (determined by URL), validate required variables
    if not base_url.startswith("http://localhost"):
        missing_vars = []
        if not config.test_user_email:
            missing_vars.append("OPIK_TEST_USER_EMAIL")
        if not config.test_user_name:
            missing_vars.append("OPIK_TEST_USER_NAME")
        if not config.test_user_password:
            missing_vars.append("OPIK_TEST_USER_PASSWORD")

        if missing_vars:
            raise ValueError(
                f"Missing required environment variables for non-local environment: {', '.join(missing_vars)}"
            )

    return config
