import logging
import importlib.metadata
from functools import cached_property
from typing import Any, Dict, List, Optional, Set
import warnings

import litellm
from litellm.types.utils import ModelResponse
from litellm.integrations.opik.opik import OpikLogger

from . import base_model
from ... import opik_context
from opik import semantic_version

LOGGER = logging.getLogger(__name__)
litellm.suppress_debug_info = True  # to disable colorized prints with links to litellm whenever an LLM provider raises an error


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
            model_name: The name of the LLM model to be used.
            must_support_arguments: A list of arguments that the provider must support.
                `litellm.get_supported_openai_params(model_name)` call is used to get
                supported arguments. If any is missing, ValueError is raised.
            **completion_kwargs: key-value arguments to always pass additionally into `litellm.completion` function.
        """

        super().__init__(model_name=model_name)

        self._check_model_name()
        self._check_must_support_arguments(must_support_arguments)

        self._completion_kwargs: Dict[str, Any] = self._filter_supported_params(
            completion_kwargs
        )

        self._add_warning_filters()
        self._engine = litellm

    def _add_warning_filters(self) -> None:
        # TODO: This should be removed when we have fixed the error messages in the LiteLLM library
        warnings.filterwarnings("ignore", message="coroutine '.*' was never awaited")
        warnings.filterwarnings(
            "ignore",
            message="Enable tracemalloc to get the object allocation traceback",
        )

        class NoEventLoopFilterLiteLLM(logging.Filter):
            def filter(self, record: Any) -> bool:
                return (
                    "Asynchronous processing not initialized as we are not running in an async context"
                    not in record.getMessage()
                )

        # Add filter to multiple possible loggers
        filter = NoEventLoopFilterLiteLLM()
        logging.getLogger("LiteLLM").addFilter(filter)

    @cached_property
    def supported_params(self) -> Set[str]:
        supported_params = set(
            litellm.get_supported_openai_params(model=self.model_name)
        )
        self._ensure_supported_params(supported_params)

        # Add metadata and success_callback as a parameter that is always supported
        supported_params.add("metadata")
        supported_params.add("success_callback")

        return supported_params

    def _ensure_supported_params(self, params: Set[str]) -> None:
        """
        LiteLLM may have broken support for some parameters. If we detect it, we
        can add custom filtering to ensure that model call will not fail.
        """
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

    def _filter_supported_params(self, params: Dict[str, Any]) -> Dict[str, Any]:
        valid_params = {}

        for key, value in params.items():
            if key not in self.supported_params:
                LOGGER.debug(
                    f"This model does not support the '{key}' parameter and it has been ignored."
                )
            else:
                valid_params[key] = value

        return valid_params

    def _add_span_metadata_to_params(self, params: Dict[str, Any]) -> Dict[str, Any]:
        current_span = opik_context.get_current_span_data()

        if current_span is None:
            return params

        if "current_span_data" in params.get("metadata", {}).get("opik", {}):
            return params

        return {
            **params,
            "metadata": {
                **params.get("metadata", {}),
                "opik": {
                    **params.get("metadata", {}).get("opik", {}),
                    "current_span_data": current_span,
                },
            },
        }

    def _add_success_callback_to_params(self, params: Dict[str, Any]) -> Dict[str, Any]:
        has_global_opik_logger = any(
            isinstance(callback, OpikLogger) for callback in litellm.callbacks
        )

        has_local_opik_logger = any(
            isinstance(callback, OpikLogger)
            for callback in params.get("success_callback", [])
        )

        if has_global_opik_logger or has_local_opik_logger:
            return params
        else:
            opik_logger = OpikLogger()

            return {
                **params,
                "success_callback": [opik_logger, *params.get("success_callback", [])],
            }

    def _add_opik_monitoring(self, params: Dict[str, Any]) -> Dict[str, Any]:
        params = self._add_span_metadata_to_params(params)
        params = self._add_success_callback_to_params(params)
        return params

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

        valid_litellm_params = self._filter_supported_params(kwargs)

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
        **kwargs: Any,
    ) -> ModelResponse:
        """
        Generate a provider-specific response. Can be used to interface with
        the underlying model provider (e.g., OpenAI, Anthropic) and get raw output.
        You can find all possible input parameters here: https://docs.litellm.ai/docs/completion/input

        Args:
            kwargs: arguments required by the provider to generate a response.

        Returns:
            Any: The response from the model provider, which can be of any type depending on the use case and LLM model.
        """

        # we need to pop messages first, and after we will check the rest params
        messages = kwargs.pop("messages")

        valid_litellm_params = self._filter_supported_params(kwargs)
        all_kwargs = {**self._completion_kwargs, **valid_litellm_params}

        all_kwargs = self._add_opik_monitoring(all_kwargs)

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

        valid_litellm_params = self._filter_supported_params(kwargs)

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

    async def agenerate_provider_response(self, **kwargs: Any) -> ModelResponse:
        """
        Generate a provider-specific response. Can be used to interface with
        the underlying model provider (e.g., OpenAI, Anthropic) and get raw output. Async version.
        You can find all possible input parameters here: https://docs.litellm.ai/docs/completion/input

        Args:
            kwargs: arguments required by the provider to generate a response.

        Returns:
            Any: The response from the model provider, which can be of any type depending on the use case and LLM model.
        """

        # we need to pop messages first, and after we will check the rest params
        messages = kwargs.pop("messages")

        valid_litellm_params = self._filter_supported_params(kwargs)
        all_kwargs = {**self._completion_kwargs, **valid_litellm_params}

        all_kwargs = self._add_opik_monitoring(all_kwargs)

        response = await self._engine.completion(
            model=self.model_name, messages=messages, **all_kwargs
        )

        return response
