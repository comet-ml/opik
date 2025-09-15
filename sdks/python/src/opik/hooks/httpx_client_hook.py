import logging

import httpx
from typing import Any, Callable, List, Optional, Dict


_deprecated_httpx_client_hooks: List[Callable[[httpx.Client], httpx.Client]] = []

# holder for the global httpx client hook
_httpx_client_hooks: List["HttpxClientHook"] = []


LOGGER = logging.getLogger(__name__)


class HttpxClientHook:
    def __init__(
        self,
        client_modifier: Optional[Callable[[httpx.Client], None]],
        client_init_arguments: Optional[Dict[str, Any]],
    ) -> None:
        """Provides a means to customize an `httpx.Client` instance used by Opik.

        This class allows users to attach a callable hook to modify or interact
        with an `httpx.Client` instance and pass initialization arguments to
        create customized client configurations. The primary usage is to allow
        pre-processing or setup of HTTP clients used in a broader application.

        Args:
            client_modifier: Optional callable that accepts an `httpx.Client` instance and
                returns a modified httpx.Client instance.
            client_init_arguments: Dictionary containing additional `httpx.Client`
                initialization arguments to be passed to the default `httpx.Client`.
        """
        self._hook = client_modifier
        self._httpx_client_arguments = client_init_arguments

    def update_init_arguments(self, kwargs: Dict[str, Any]) -> Dict[str, Any]:
        if self._httpx_client_arguments is not None:
            kwargs.update(self._httpx_client_arguments)

        return kwargs

    def __call__(self, client: httpx.Client) -> None:
        if self._hook is not None:
            self._hook(client)


def add_httpx_client_hook(hook: HttpxClientHook) -> None:
    """
    Adds an HttpxClientHook to the list of hooks to be used with HTTPX clients.

    Injects a new hook into the global list of HTTPX client hooks, allowing for
    custom behavior or additional functionality when making requests with
    an HTTPX client.

    Args:
        hook (HttpxClientHook): A callable to be added as an HTTPX client hook.

    """
    global _httpx_client_hooks
    _httpx_client_hooks.append(hook)


def build_init_arguments(default_kwargs: Dict[str, Any]) -> Dict[str, Any]:
    """
    Modifies and returns initialization arguments by applying pre-defined hooks.

    This function iterates through a collection of hooks and applies their logic to
    update the initialization arguments provided.

    Args:
        default_kwargs: A dictionary containing default initialization
            arguments.

    Returns:
        Dict[str, Any]: The modified dictionary of initialization arguments.
    """
    for hook in _httpx_client_hooks:
        default_kwargs = hook.update_init_arguments(default_kwargs)

    return default_kwargs


def apply_httpx_client_hooks(client: httpx.Client) -> None:
    """Applies registered httpx client hooks."""
    for hook in _httpx_client_hooks:
        hook(client)

    # apply deprecated hooks if any
    for deprecated_hook in _deprecated_httpx_client_hooks:
        deprecated_hook(client)


def register_httpx_client_hook(hook: Callable[[httpx.Client], httpx.Client]) -> None:
    """
    Deprecated: This method is deprecated and will be removed in a future release. Please use `add_httpx_client_hook` instead.

    Registers a hook for the customization of `httpx.Client` instances. The provided
    hook function will be invoked with an `httpx.Client` instance and is expected
    to return a customized `httpx.Client`.


    Args:
        hook: A callable that takes an `httpx.Client` instance and returns a
              customized `httpx.Client`.
    """
    _deprecated_httpx_client_hooks.append(hook)

    LOGGER.warning(
        "register_httpx_client_hook is deprecated and will be removed in a future release. Please use add_httpx_client_hook instead."
    )
