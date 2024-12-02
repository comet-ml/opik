import httpx
from typing import Callable, List

_registered_httpx_client_hooks: List[Callable[[httpx.Client], None]] = []


def register_httpx_client_hook(hook: Callable[[httpx.Client], httpx.Client]) -> None:
    _registered_httpx_client_hooks.append(hook)


def run_httpx_client_hooks(client: httpx.Client) -> None:
    for hook in _registered_httpx_client_hooks:
        hook(client)
