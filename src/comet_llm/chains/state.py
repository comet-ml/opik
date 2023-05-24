from . import chain

_CHAIN = None
_ID = 0


def get_global_chain() -> chain.Chain:
    global _CHAIN
    if _CHAIN is None:
        _CHAIN = chain.Chain()

    return _CHAIN


def get_new_id() -> int:
    global _ID
    _ID += 1
    return _ID
