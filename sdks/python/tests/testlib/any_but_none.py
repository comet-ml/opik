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


ANY_BUT_NONE = AnyButNone()
