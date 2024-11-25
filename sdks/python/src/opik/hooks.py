import httpx
from typing import Callable, List

_registered_hooks: List[Callable[[httpx.Client], None]] = []


def register_httpx_client_hook(hook: Callable[[httpx.Client], httpx.Client]):
    _registered_hooks.append(hook)


def run_httpx_client_hooks(client: httpx.Client) -> None:
    for hook in _registered_hooks:
        hook(client)
