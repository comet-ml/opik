"""
Hooks for pytest-httpserver
"""

import os
import time
from typing import Callable

from werkzeug import Request
from werkzeug import Response


class Chain:
    """
    Combine multiple hooks into one callable object

    Hooks specified will be called one by one.

    Each hook will receive the response object made by the previous hook,
    similar to reduce.
    """

    def __init__(self, *args: Callable[[Request, Response], Response]):
        """
        :param *args: callable objects specified in the same order they should
            be called.
        """
        self._hooks = args

    def __call__(self, request: Request, response: Response) -> Response:
        """
        Calls the callable object one by one. The second and further callable
        objects receive the response returned by the previous one, while the
        first one receives the original response object.
        """
        for hook in self._hooks:
            response = hook(request, response)
        return response


class Delay:
    """
    Delays returning the response
    """

    def __init__(self, seconds: float):
        """
        :param seconds: seconds to sleep before returning the response
        """
        self._seconds = seconds

    def _sleep(self):
        """
        Sleeps for the seconds specified in the constructor
        """
        time.sleep(self._seconds)

    def __call__(self, _request: Request, response: Response) -> Response:
        """
        Delays returning the response object for the time specified in the
        constructor. Returns the original response unmodified.
        """
        self._sleep()
        return response


class Garbage:
    def __init__(self, prefix_size: int = 0, suffix_size: int = 0):
        """
        Adds random bytes to the beginning or to the end of the response data.

        :param prefix_size: amount of random bytes to be added to the beginning
            of the response data

        :param suffix_size: amount of random bytes to be added to the end
            of the response data

        """
        assert prefix_size >= 0, "prefix_size should be positive integer"
        assert suffix_size >= 0, "suffix_size should be positive integer"
        self._prefix_size = prefix_size
        self._suffix_size = suffix_size

    def _get_garbage_bytes(self, size: int) -> bytes:
        """
        Returns the specified amount of random bytes.

        :param size: amount of bytes to return
        """
        return os.urandom(size)

    def __call__(self, _request: Request, response: Response) -> Response:
        """
        Adds random bytes to the beginning or to the end of the response data.

        New random bytes will be generated for every call.

        Returns the modified response object.
        """
        prefix = self._get_garbage_bytes(self._prefix_size)
        suffix = self._get_garbage_bytes(self._suffix_size)
        response.set_data(prefix + response.get_data() + suffix)
        return response
