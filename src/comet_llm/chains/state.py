from . import chain

_CHAIN = None
_ID = 0

def get_global_chain():
    global _CHAIN
    if _CHAIN is None:
        _CHAIN = chain.Chain()

    return _CHAIN


def get_new_id():
    global _ID
    _ID += 1
    return _ID