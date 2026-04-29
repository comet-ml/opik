import importlib.metadata
import logging
import warnings
from functools import cached_property
from typing import Any, cast, Dict, List, Optional, Set, TYPE_CHECKING, Type
import pydantic
import tenacity

if TYPE_CHECKING:
    from litellm.types.utils import ModelResponse

import opik.semantic_version as semantic_version
import opik.config as opik_config

from .. import base_model
from . import warning_filters, response_parser, util
from opik import exceptions

LOGGER = logging.getLogger(__name__)


def _log_warning(message: str, *args: Any) -> None:
    """Emit a warning to both this module logger and the root logger.

    pytest's logging capture hooks into the root logger, while production runs use
    the module-level logger. Logging to both keeps warnings visible in tests and at
    runtime without duplicating call sites.
    """

    LOGGER.warning(message, *args)
    root_logger = logging.getLogger()
    if root_logger is not LOGGER:
        root_logger.log(logging.WARNING, message, *args)


class LiteLLMChatModel(base_model.OpikBaseModel):
    def __init__(
        self,
        model_name: str = "gpt-5-nano",
        must_support_arguments: Optional[List[str]] = None,
        track: bool = True,
        **completion_kwargs: Any,
    ) -> None:
        import litellm

        """
        Initializes the base model with a given model name.
        You can find all possible completion_kwargs parameters here: https://docs.litellm.ai/docs/completion/input.

        Args:
            model_name: The name of the LLM to be used.
                This parameter will be passed to `litellm.completion(model=model_name)` so you don't need to pass
                the `model` argument separately inside completion_kwargs.
            must_support_arguments: A list of openai-like arguments that the given model + provider pair must support.
                `litellm.get_supported_openai_params(model_name)` call is used to get
                supported arguments. If any is missing, ValueError is raised.
                You can pass the arguments from the table: https://docs.litellm.ai/docs/completion/input#translated-openai-params
            track: Whether to track the model calls. When False, disables tracing for this model instance.
                Defaults to True.
            completion_kwargs: key-value arguments to always pass additionally into `litellm.completion` function.
        """
        super().__init__(model_name=model_name)

        self._check_model_name()
        self._check_must_support_arguments(must_support_arguments)

        self._unsupported_warned: Set[str] = set()

        self._completion_kwargs: Dict[str, Any] = (
            self._remove_unnecessary_not_supported_params(completion_kwargs)
        )

        with warnings.catch_warnings():
            # This is the first time litellm is imported when opik is imported.
            # It filters out pydantic warning.
            # Litellm has already fixed that, but it is not released yet, so this filter
            # should be removed from here soon.
            warnings.simplefilter("ignore")

        warning_filters.add_warning_filters()

        config = opik_config.OpikConfig()

        # Enable tracking only if both track parameter is True and config allows it
        enable_tracking = track and config.enable_litellm_models_monitoring

        if enable_tracking:
            import opik.integrations.litellm as litellm_integration

            self._litellm_completion = litellm_integration.track_completion()(
                litellm.completion
            )
            self._litellm_acompletion = litellm_integration.track_completion()(
                litellm.acompletion
            )
        else:
            self._litellm_completion = litellm.completion
            self._litellm_acompletion = litellm.acompletion

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

        # Fix for impacted providers like Groq and OpenAI
        if (
            "response_format" in params
            and "response_format" not in self.supported_params
        ):
            filtered_params.pop("response_format")
            LOGGER.debug(
                "This model does not support the response_format parameter and it will be ignored."
            )
        if (
            "reasoning_effort" in params
            and "reasoning_effort" not in self.supported_params
        ):
            filtered_params.pop("reasoning_effort")
            LOGGER.debug(
                "Model %s does not support reasoning_effort, dropping.",
                self.model_name,
            )
        # NOTE: Filtering based on `supported_params` has been disabled temporarily
        # because LiteLLM does not surface provider-specific connection fields via
        # `get_supported_openai_params`. Dropping those kwargs breaks Azure/Groq
        # users who rely on parameters such as `api_version` and `azure_endpoint`.
        # The old logic is kept here commented for future restoration.
        #
        # for key in list(filtered_params.keys()):
        #     if (
        #         key not in self.supported_params
        #         and not util.should_preserve_provider_param(key)
        #     ):
        #         filtered_params.pop(key)
        #         if key not in self._unsupported_warned:
        #             _log_warning(
        #                 "Parameter '%s' is not supported by model %s and will be ignored.",
        #                 key,
        #                 self.model_name,
        #             )
        #             self._unsupported_warned.add(key)

        util.apply_model_specific_filters(
            model_name=self.model_name,
            params=filtered_params,
            already_warned=self._unsupported_warned,
            warn=self._warn_about_unsupported_param,
        )

        return filtered_params

    def _warn_about_unsupported_param(self, param: str, value: Any) -> None:
        if param in {"logprobs", "top_logprobs"}:
            # LiteLLM warns noisily when models like gpt-5-nano don't support these
            # fields. We already drop them gracefully, so skip logging to avoid
            # spamming GEval users with repeated warnings.
            return
        if param == "temperature":
            _log_warning(
                "Model %s only supports temperature=1. Dropping temperature=%s.",
                self.model_name,
                value,
            )
        else:
            _log_warning(
                "Model %s does not support %s. Dropping the parameter.",
                self.model_name,
                param,
            )

    def generate_string(
        self,
        input: str,
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> str:
        """
        Simplified interface to generate a string output from the model.
        You can find all possible completion_kwargs parameters here: https://docs.litellm.ai/docs/completion/input

        Args:
            input: The input string based on which the model will generate the output.
            response_format: pydantic model specifying the expected output string format.
            kwargs: Additional arguments that may be used by the model for string generation.

        Returns:
            str: The generated string output.
        """
        message = self.generate_chat_completion(
            messages=[{"role": "user", "content": input}],
            response_format=response_format,
            **kwargs,
        )
        return message["content"]

    def generate_chat_completion(
        self,
        messages: List[base_model.ConversationDict],
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> base_model.ConversationDict:
        """
        Generate the assistant turn from a list of chat messages forwarded to the provider verbatim.

        Use this when you want a stable ``system`` prefix across calls so that
        provider-side prompt caching can take effect (judge metrics, suite
        evaluators).

        Args:
            messages: A list of ``{"role": ..., "content": ...}`` dictionaries.
            response_format: Optional Pydantic model specifying the expected output format.
            kwargs: Additional arguments forwarded to ``litellm.completion``.

        Returns:
            ``{"role": "assistant", "content": ...}``.
        """
        if response_format is not None:
            kwargs["response_format"] = response_format

        valid_litellm_params = self._remove_unnecessary_not_supported_params(kwargs)

        with base_model.get_provider_response(
            model_provider=self,
            messages=cast(List[Dict[str, Any]], list(messages)),
            **valid_litellm_params,
        ) as response:
            return response_parser.parse_assistant_message(response)

    def generate_provider_response(
        self,
        messages: List[Dict[str, Any]],
        **kwargs: Any,
    ) -> "ModelResponse":
        """
        Do not use this method directly. It is intended to be used within `base_model.get_provider_response()` method.

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

        # Extract retry configuration before filtering params
        retries = kwargs.pop("__opik_retries", 3)
        try:
            max_attempts = max(1, int(retries))
        except (TypeError, ValueError):
            max_attempts = 1

        # we need to pop messages first, and after we will check the rest params
        valid_litellm_params = self._remove_unnecessary_not_supported_params(kwargs)
        all_kwargs = {**self._completion_kwargs, **valid_litellm_params}

        retrying = tenacity.Retrying(
            reraise=True,
            stop=tenacity.stop_after_attempt(max_attempts),
            wait=tenacity.wait_exponential(multiplier=0.5, min=0.5, max=8.0),
        )

        return retrying(
            self._litellm_completion,
            model=self.model_name,
            messages=messages,
            **all_kwargs,
        )

    async def agenerate_string(
        self,
        input: str,
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> str:
        """
        Simplified interface to generate a string output from the model. Async version.
        You can find all possible input parameters here: https://docs.litellm.ai/docs/completion/input

        Args:
            input: The input string based on which the model will generate the output.
            response_format: pydantic model specifying the expected output string format.
            kwargs: Additional arguments that may be used by the model for string generation.

        Returns:
            str: The generated string output.
        """
        message = await self.agenerate_chat_completion(
            messages=[{"role": "user", "content": input}],
            response_format=response_format,
            **kwargs,
        )
        return message["content"]

    async def agenerate_chat_completion(
        self,
        messages: List[base_model.ConversationDict],
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> base_model.ConversationDict:
        """
        Async counterpart of :meth:`generate_chat_completion`.
        """
        if response_format is not None:
            kwargs["response_format"] = response_format

        valid_litellm_params = self._remove_unnecessary_not_supported_params(kwargs)

        async with base_model.aget_provider_response(
            model_provider=self,
            messages=cast(List[Dict[str, Any]], list(messages)),
            **valid_litellm_params,
        ) as response:
            return response_parser.parse_assistant_message(response)

    async def agenerate_provider_response(
        self, messages: List[Dict[str, Any]], **kwargs: Any
    ) -> "ModelResponse":
        """
        Do not use this method directly. It is intended to be used within `base_model.aget_provider_response()` method.

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

        retries = kwargs.pop("__opik_retries", 3)
        try:
            max_attempts = max(1, int(retries))
        except (TypeError, ValueError):
            max_attempts = 1

        valid_litellm_params = self._remove_unnecessary_not_supported_params(kwargs)
        all_kwargs = {**self._completion_kwargs, **valid_litellm_params}

        retrying = tenacity.AsyncRetrying(
            reraise=True,
            stop=tenacity.stop_after_attempt(max_attempts),
            wait=tenacity.wait_exponential(multiplier=0.5, min=0.5, max=8.0),
        )

        async for attempt in retrying:
            with attempt:
                return await self._litellm_acompletion(
                    model=self.model_name, messages=messages, **all_kwargs
                )

        raise exceptions.BaseLLMError(
            "Async LLM completion failed without raising an exception"
        )  # pragma: no cover
