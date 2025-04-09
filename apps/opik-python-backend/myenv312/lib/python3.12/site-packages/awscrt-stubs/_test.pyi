"""
Type annotations for awscrt._test module.

Copyright 2024 Vlad Emelianov
"""

from awscrt import NativeResource as NativeResource
from awscrt.io import ClientBootstrap as ClientBootstrap
from awscrt.io import DefaultHostResolver as DefaultHostResolver
from awscrt.io import EventLoopGroup as EventLoopGroup

def native_memory_usage() -> int: ...
def dump_native_memory() -> None: ...
def join_all_native_threads(*, timeout_sec: float = ...) -> bool: ...
def check_for_leaks(*, timeout_sec: float = ...) -> None: ...
