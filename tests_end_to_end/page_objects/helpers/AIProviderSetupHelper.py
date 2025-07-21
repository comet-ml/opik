import os
import logging
import pytest
from page_objects.AIProvidersConfigPage import AIProvidersConfigPage

logger = logging.getLogger(__name__)


class AIProviderSetupHelper:
    """Helper class to handle AI provider setup operations across different pages"""

    def __init__(self, page):
        self.page = page
        self.ai_providers_page = AIProvidersConfigPage(page)

    def setup_provider_if_needed(self, provider_name: str, provider_config):
        """
        Set up AI provider configuration if it doesn't already exist.

        Args:
            provider_name: The provider name (e.g., "openai", "anthropic")
            provider_config: The provider configuration object
        """
        logger.info(f"Setting up AI provider for {provider_config.display_name}")
        self.ai_providers_page.go_to_page()

        # Check if provider already exists
        if self.ai_providers_page.check_provider_exists(
            provider_config.api_key_env_var
        ):
            logger.info(
                f"AI provider {provider_config.display_name} already exists, skipping setup"
            )
            return

        # Get API key from environment
        api_key = os.getenv(provider_config.api_key_env_var)
        if not api_key:
            pytest.skip(
                f"API key not found for {provider_config.display_name} (env var: {provider_config.api_key_env_var})"
            )

        # Add provider configuration
        self.ai_providers_page.add_provider(
            provider_type=provider_name.lower(), api_key=api_key
        )

        logger.info(
            f"Successfully set up AI provider for {provider_config.display_name}"
        )

    def cleanup_provider(self, provider_config):
        """
        Clean up AI provider configuration after testing.

        Args:
            provider_config: The provider configuration object
        """
        logger.info(
            f"Cleaning up AI provider configuration for {provider_config.display_name}"
        )
        self.ai_providers_page.go_to_page()
        try:
            self.ai_providers_page.delete_provider(
                provider_name=provider_config.api_key_env_var
            )
        except Exception as e:
            logger.warning(f"Failed to clean up provider configuration: {e}")
