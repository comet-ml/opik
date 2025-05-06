from typing import Optional

from unittest import mock


class AnyButNone:
    """A helper object that compares equal to everything but None."""

    def __eq__(self, other):
        if other is None:
            return False

        return True

    def __ne__(self, other):
        return not self.__eq__(other)

    def __repr__(self):
        return "<ANY_BUT_NONE>"


class AnyDict:
    """A helper object that compares equal to all dicts."""

    def __eq__(self, other):
        if isinstance(other, dict):
            return True

        return False

    def __ne__(self, other):
        return not self.__eq__(other)

    def __repr__(self):
        return "<ANY_DICT>"


class AnyList:
    """A helper object that compares equal to all lists."""

    def __eq__(self, other):
        if isinstance(other, list):
            return True

        return False

    def __ne__(self, other):
        return not self.__eq__(other)

    def __repr__(self):
        return "<ANY_LIST>"


class AnyString(str):
    """A helper object that provides partial equality check to strings."""

    def __init__(self, startswith: Optional[str] = None):
        self.startswith = startswith

    def __eq__(self, other):
        if not isinstance(other, str):
            return False

        if self.startswith is None:
            return True

        if other.startswith(self.startswith):
            return True

        return False

    def __repr__(self):
        return "<ANY_STRING>"


ANY = mock.ANY
ANY_BUT_NONE = AnyButNone()
ANY_DICT = AnyDict()
ANY_LIST = AnyList()
ANY_STRING = AnyString
