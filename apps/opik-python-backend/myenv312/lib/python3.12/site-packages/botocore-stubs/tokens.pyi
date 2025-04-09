"""
Type annotations for botocore.tokens module.

Copyright 2025 Vlad Emelianov
"""

import datetime
import logging
from typing import Any, Callable, Iterable, NamedTuple

from botocore.session import Session
from botocore.utils import JSONFileCache

logger: logging.Logger

def create_token_resolver(session: Session) -> TokenProviderChain: ...

class FrozenAuthToken(NamedTuple):
    token: str
    expiration: datetime.datetime | None = ...

class DeferredRefreshableToken:
    def __init__(
        self,
        method: Any,
        refresh_using: Callable[[], FrozenAuthToken],
        time_fetcher: Callable[[], datetime.datetime] = ...,
    ) -> None: ...
    def get_frozen_token(self) -> FrozenAuthToken: ...

class TokenProviderChain:
    def __init__(self, providers: Iterable[Any] | None = ...) -> None: ...
    def load_token(self) -> DeferredRefreshableToken: ...

class SSOTokenProvider:
    METHOD: str = ...
    DEFAULT_CACHE_CLS: type[JSONFileCache] = ...

    def __init__(
        self,
        session: Session,
        cache: JSONFileCache | None = ...,
        time_fetcher: Callable[[], datetime.datetime] = ...,
        profile_name: str | None = ...,
    ) -> None: ...
    def load_token(self) -> DeferredRefreshableToken: ...
