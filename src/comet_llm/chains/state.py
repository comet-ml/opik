from . import chain

_CHAIN = None


def get_global_chain():
    global _CHAIN
    if _CHAIN is None:
        _CHAIN = chain.Chain()

    return _CHAIN
