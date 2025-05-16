import importlib.metadata
import logging
import warnings
from functools import cached_property
from typing import Any, Dict, List, Optional, Set, TYPE_CHECKING

if TYPE_CHECKING:
    from litellm.types.utils import ModelResponse

from opik import semantic_version

from .. import base_model
from . import opik_monitor, warning_filters

LOGGER = logging.getLogger(__name__)


class LiteLLMChatModel(base_model.OpikBaseModel):
    def __init__(
        self,
        model_name: str = "gpt-3.5-turbo",
        must_support_arguments: Optional[List[str]] = None,
        **completion_kwargs: Any,
    ) -> None:
        """
        Initializes the base model with a given model name.
        Wraps `litellm.completion` function.
        You can find all possible completion_kwargs parameters here: https://docs.litellm.ai/docs/completion/input.

        Args:
            model_name: The name of the LLM to be used.
                This parameter will be passed to `litellm.completion(model=model_name)` so you don't need to pass
                the `model` argument separately inside completion_kwargs.
            must_support_arguments: A list of openai-like arguments that the given model + provider pair must support.
                `litellm.get_supported_openai_params(model_name)` call is used to get
                supported arguments. If any is missing, ValueError is raised.
                You can pass the arguments from the table: https://docs.litellm.ai/docs/completion/input#translated-openai-params

            completion_kwargs: key-value arguments to always pass additionally into `litellm.completion` function.
        """

        super().__init__(model_name=model_name)

        self._check_model_name()
        self._check_must_support_arguments(must_support_arguments)

        self._completion_kwargs: Dict[str, Any] = (
            self._remove_unnecessary_not_supported_params(completion_kwargs)
        )

        with warnings.catch_warnings():
            # This is the first time litellm is imported when opik is imported.
            # It filters out pydantic warning.
            # Litellm has already fixed that, but it is not released yet, so this filter
            # should be removed from here soon.
            warnings.simplefilter("ignore")
            import litellm

        warning_filters.add_warning_filters()

        self._engine = litellm

    @cached_property
    def supported_params(self) -> Set[str]:
        import litellm

        supported_params = set(
            litellm.get_supported_openai_params(model=self.model_name)
        )
        self._ensure_supported_params(supported_params)

        return supported_params

    def _ensure_supported_params(self, params: Set[str]) -> None:
        """
        LiteLLM may have broken support for some parameters. If we detect it, we
        can add custom filtering to ensure that model call will not fail.
        """
        import litellm

        provider = litellm.get_llm_provider(self.model_name)[1]

        if provider not in ["groq", "ollama"]:
            return

        litellm_version = importlib.metadata.version("litellm")
        if semantic_version.SemanticVersion.parse(litellm_version) < "1.52.15":  # type: ignore
            params.discard("response_format")
            LOGGER.warning(
                "LiteLLM version %s does not support structured outputs for %s provider. We recomment updating to at least 1.52.15 for a more robust metrics calculation.",
                litellm_version,
                provider,
            )

    def _check_model_name(self) -> None:
        import litellm

        try:
            _ = litellm.get_llm_provider(self.model_name)
        except litellm.exceptions.BadRequestError:
            raise ValueError(f"Unsupported model: '{self.model_name}'!")

    def _check_must_support_arguments(self, args: Optional[List[str]]) -> None:
        if args is None:
            return

        for key in args:
            if key not in self.supported_params:
                raise ValueError(f"Unsupported parameter: '{key}'!")

    def _remove_unnecessary_not_supported_params(
        self, params: Dict[str, Any]
    ) -> Dict[str, Any]:
        filtered_params = {**params}

        if (
            "response_format" in params
            and "response_format" not in self.supported_params
        ):
            filtered_params.pop("response_format")
            LOGGER.debug(
                "This model does not support the response_format parameter and it will be ignored."
            )

        return filtered_params

    def generate_string(self, input: str, **kwargs: Any) -> str:
        """
        Simplified interface to generate a string output from the model.
        You can find all possible completion_kwargs parameters here: https://docs.litellm.ai/docs/completion/input

        Args:
            input: The input string based on which the model will generate the output.
            kwargs: Additional arguments that may be used by the model for string generation.

        Returns:
            str: The generated string output.
        """

        valid_litellm_params = self._remove_unnecessary_not_supported_params(kwargs)

        request = [
            {
                "content": input,
                "role": "user",
            },
        ]

        response = self.generate_provider_response(
            messages=request, **valid_litellm_params
        )
        return response.choices[0].message.content

    def generate_provider_response(
        self,
        messages: List[Dict[str, Any]],
        **kwargs: Any,
    ) -> "ModelResponse":
        """
        Generate a provider-specific response. Can be used to interface with
        the underlying model provider (e.g., OpenAI, Anthropic) and get raw output.
        You can find all possible input parameters here: https://docs.litellm.ai/docs/completion/input

        Args:
            messages: A list of messages to be sent to the model, should be a list of dictionaries with the keys
                "content" and "role".
            kwargs: arguments required by the provider to generate a response.

        Returns:
            Any: The response from the model provider, which can be of any type depending on the use case and LLM.
        """

        # we need to pop messages first, and after we will check the rest params
        valid_litellm_params = self._remove_unnecessary_not_supported_params(kwargs)
        all_kwargs = {**self._completion_kwargs, **valid_litellm_params}

        if (
            opik_monitor.enabled_in_config()
            and not opik_monitor.opik_is_misconfigured()
        ):
            all_kwargs = opik_monitor.try_add_opik_monitoring_to_params(all_kwargs)

        response = self._engine.completion(
            model=self.model_name, messages=messages, **all_kwargs
        )

        return response

    async def agenerate_string(self, input: str, **kwargs: Any) -> str:
        """
        Simplified interface to generate a string output from the model. Async version.
        You can find all possible input parameters here: https://docs.litellm.ai/docs/completion/input

        Args:
            input: The input string based on which the model will generate the output.
            kwargs: Additional arguments that may be used by the model for string generation.

        Returns:
            str: The generated string output.
        """

        valid_litellm_params = self._remove_unnecessary_not_supported_params(kwargs)

        request = [
            {
                "content": input,
                "role": "user",
            },
        ]

        response = await self.agenerate_provider_response(
            messages=request, **valid_litellm_params
        )
        return response.choices[0].message.content

    async def agenerate_provider_response(
        self, messages: List[Dict[str, Any]], **kwargs: Any
    ) -> "ModelResponse":
        """
        Generate a provider-specific response. Can be used to interface with
        the underlying model provider (e.g., OpenAI, Anthropic) and get raw output. Async version.
        You can find all possible input parameters here: https://docs.litellm.ai/docs/completion/input

        Args:
            messages: A list of messages to be sent to the model, should be a list of dictionaries with the keys
                "content" and "role".
            kwargs: arguments required by the provider to generate a response.

        Returns:
            Any: The response from the model provider, which can be of any type depending on the use case and LLM.
        """

        valid_litellm_params = self._remove_unnecessary_not_supported_params(kwargs)
        all_kwargs = {**self._completion_kwargs, **valid_litellm_params}

        if opik_monitor.enabled_in_config():
            all_kwargs = opik_monitor.try_add_opik_monitoring_to_params(all_kwargs)

        response = await self._engine.acompletion(
            model=self.model_name, messages=messages, **all_kwargs
        )

        return response
