import yaml
import os
import logging
from typing import Dict, List, Tuple, Any
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass
class ModelConfig:
    name: str
    ui_selector: str
    enabled: bool
    test_playground: bool
    test_online_scoring: bool


@dataclass
class ProviderConfig:
    display_name: str
    api_key_env_var: str
    models: List[ModelConfig]
    additional_env_vars: List[str] = field(default_factory=list)


class ModelConfigLoader:
    """Loads and manages model configurations for testing"""

    def __init__(self, config_path: str = ""):
        if not config_path:
            # Default to models_config.yaml in the same directory as this file
            config_path = os.path.join(
                os.path.dirname(__file__), "..", "models_config.yaml"
            )

        self.config_path = config_path
        self.config = self._load_config()

    def _load_config(self) -> Dict[str, Any]:
        """Load the YAML configuration file"""
        try:
            with open(self.config_path, "r") as f:
                return yaml.safe_load(f)
        except FileNotFoundError:
            logger.error(f"Configuration file not found: {self.config_path}")
            raise
        except yaml.YAMLError as e:
            logger.error(f"Error parsing YAML configuration: {e}")
            raise

    def get_providers(self) -> Dict[str, ProviderConfig]:
        """Get all provider configurations"""
        providers = {}
        for provider_name, provider_data in self.config.get("providers", {}).items():
            models = []
            for model_data in provider_data.get("models", []):
                models.append(ModelConfig(**model_data))

            providers[provider_name] = ProviderConfig(
                display_name=provider_data["display_name"],
                api_key_env_var=provider_data["api_key_env_var"],
                models=models,
                additional_env_vars=provider_data.get("additional_env_vars", []),
            )
        return providers

    def get_enabled_models_for_playground(
        self,
    ) -> List[Tuple[str, ModelConfig, ProviderConfig]]:
        """Get all enabled models that should be tested in playground"""
        models = []
        providers = self.get_providers()

        for provider_name, provider_config in providers.items():
            if not self._provider_has_api_keys(provider_config):
                if self.config.get("test_config", {}).get(
                    "skip_missing_api_keys", True
                ):
                    logger.info(f"Skipping {provider_name} - missing API keys")
                    continue

            for model in provider_config.models:
                if (
                    model.enabled
                    and model.test_playground
                    and self.config.get("test_config", {}).get(
                        "only_test_enabled", True
                    )
                ):
                    models.append((provider_name, model, provider_config))

        return models

    def get_enabled_models_for_online_scoring(
        self,
    ) -> List[Tuple[str, ModelConfig, ProviderConfig]]:
        """Get all enabled models that should be tested in online scoring"""
        models = []
        providers = self.get_providers()

        for provider_name, provider_config in providers.items():
            if not self._provider_has_api_keys(provider_config):
                if self.config.get("test_config", {}).get(
                    "skip_missing_api_keys", True
                ):
                    logger.info(f"Skipping {provider_name} - missing API keys")
                    continue

            for model in provider_config.models:
                if (
                    model.enabled
                    and model.test_online_scoring
                    and self.config.get("test_config", {}).get(
                        "only_test_enabled", True
                    )
                ):
                    models.append((provider_name, model, provider_config))

        return models

    def _provider_has_api_keys(self, provider_config: ProviderConfig) -> bool:
        """Check if a provider has all required API keys set"""
        # Check main API key
        if not os.getenv(provider_config.api_key_env_var):
            return False

        # Check additional environment variables if any
        if provider_config.additional_env_vars:
            for env_var in provider_config.additional_env_vars:
                if not os.getenv(env_var):
                    return False

        return True

    def get_test_config(self) -> Dict[str, Any]:
        """Get test configuration settings"""
        return self.config.get("test_config", {})

    def get_test_prompt(self) -> str:
        """Get the test prompt for playground tests"""
        return self.get_test_config().get(
            "test_prompt", "Explain what is an LLM in one paragraph."
        )

    def get_response_timeout(self) -> int:
        """Get the response timeout in seconds"""
        return self.get_test_config().get("response_timeout", 30)


# Global instance for easy access
model_config_loader = ModelConfigLoader()
