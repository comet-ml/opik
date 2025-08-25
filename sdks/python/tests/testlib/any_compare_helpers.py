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

    def __init__(
        self, startswith: Optional[str] = None, containing: Optional[str] = None
    ):
        self._startswith = startswith
        self._containing = containing

    def __eq__(self, other):
        if not isinstance(other, str):
            return False

        if self._startswith is None and self._containing is None:
            return True

        if self._startswith is not None and not other.startswith(self._startswith):
            return False

        if self._containing is not None and self._containing not in other:
            return False

        return True

    def __repr__(self):
        conditions = []
        if self._startswith is not None:
            conditions.append(f"startswith='{self._startswith}'")
        if self._containing is not None:
            conditions.append(f"containing='{self._containing}'")

        if not conditions:
            return "<ANY_STRING>"

        return f"<ANY_STRING({', '.join(conditions)})>"

    def starting_with(self, startswith: str):
        return AnyString(startswith=startswith, containing=self._containing)

    def containing(self, containing: str):
        return AnyString(startswith=self._startswith, containing=containing)


ANY = mock.ANY
ANY_BUT_NONE = AnyButNone()
ANY_DICT = AnyDict()
ANY_LIST = AnyList()
ANY_STRING = AnyString()
