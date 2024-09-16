class AnyButNone:
    "A helper object that compares equal to everything but None."

    def __eq__(self, other):
        if other is None:
            return False

        return True

    def __ne__(self, other):
        return not self.__eq__(other)

    def __repr__(self):
        return "<ANY_BUT_NONE>"


class AnyDict:
    "A helper object that compares equal to all dicts."

    def __eq__(self, other):
        if isinstance(other, dict):
            return True

        return False

    def __ne__(self, other):
        return not self.__eq__(other)

    def __repr__(self):
        return "<ANY_DICT>"


ANY_BUT_NONE = AnyButNone()
ANY_DICT = AnyDict()
