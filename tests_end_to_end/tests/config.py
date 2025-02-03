from dataclasses import dataclass
from typing import Optional
import os
from enum import Enum


class Environment(Enum):
    LOCAL = "local"
    STAGING = "staging"


@dataclass
class EnvConfig:
    api_url: str
    web_url: str
    workspace: Optional[str] = "default"
    project_name: Optional[str] = "automated_tests_project"
    api_key: Optional[str] = None
    test_user_email: Optional[str] = None
    test_user_name: Optional[str] = None
    test_user_password: Optional[str] = None


def get_environment_config(env: Environment = Environment.LOCAL) -> EnvConfig:
    """
    Get configuration for the specified environment.
    Uses environment variables for sensitive data in non-local environments.
    """
    configs = {
        Environment.LOCAL: EnvConfig(
            api_url="http://localhost:5173/api",
            web_url="http://localhost:5173",
        ),
        Environment.STAGING: EnvConfig(
            api_url="https://staging.dev.comet.com/opik/api",
            web_url="https://staging.dev.comet.com/opik",
            api_key=os.getenv("OPIK_API_KEY"),
            test_user_email=os.getenv("OPIK_TEST_USER_EMAIL"),
            test_user_name=os.getenv("OPIK_TEST_USER_NAME"),
            test_user_password=os.getenv("OPIK_TEST_USER_PASSWORD"),
            workspace=os.getenv("OPIK_TEST_USER_NAME"),
        ),
    }

    config = configs[env]

    # Validate required environment variables for non-local environments
    if env != Environment.LOCAL:
        missing_vars = []
        if not config.api_key:
            missing_vars.append("OPIK_API_KEY")
        if not config.test_user_email:
            missing_vars.append("OPIK_TEST_USER_EMAIL")
        if not config.test_user_name:
            missing_vars.append("OPIK_TEST_USER_NAME")
        if not config.test_user_password:
            missing_vars.append("OPIK_TEST_USER_PASSWORD")

        if missing_vars:
            raise ValueError(
                f"Missing required environment variables for {env.value} environment: {', '.join(missing_vars)}"
            )

    return config
