"""
Type annotations for awscrt module.

Copyright 2024 Vlad Emelianov
"""

from awscrt import auth as auth
from awscrt import crypto as crypto
from awscrt import http as http
from awscrt import io as io
from awscrt import mqtt as mqtt
from awscrt import s3 as s3
from awscrt import websocket as websocket

__all__ = [
    "auth",
    "crypto",
    "http",
    "io",
    "mqtt",
    "s3",
    "websocket",
]
__version__: str = ...

class NativeResource:
    def __init__(self) -> None: ...
