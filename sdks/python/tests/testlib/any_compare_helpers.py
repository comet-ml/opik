from typing import Optional, Dict

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

    def __init__(self, containing: Optional[Dict] = None):
        self.containing_items = containing

    def __eq__(self, other):
        if not isinstance(other, dict):
            return False

        if self.containing_items is None:
            return True

        if other.items() >= self.containing_items.items():
            return True

        return False

    def __ne__(self, other):
        return not self.__eq__(other)

    def __repr__(self):
        if self.containing_items is None:
            return "<ANY_DICT>"
        return "<ANY_DICT_WITH_CONTAIN_CONDITION>"

    def containing(self, containing: Dict):
        return AnyDict(containing)


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


class AnyString:
    """A helper object that provides partial equality check to strings."""

    def __init__(self, startswith: Optional[str] = None):
        self._startswith = startswith

    def __eq__(self, other):
        if not isinstance(other, str):
            return False

        if self._startswith is None:
            return True

        if other.startswith(self._startswith):
            return True

        return False

    def __repr__(self):
        if self._startswith is None:
            return "<ANY_STRING>"
        return "<ANY_STRING_WITH_STARTSWITH_CONDITION>"

    def starting_with(self, startswith: str):
        return AnyString(startswith=startswith)


ANY = mock.ANY
ANY_BUT_NONE = AnyButNone()
ANY_DICT = AnyDict()
ANY_LIST = AnyList()
ANY_STRING = AnyString()
