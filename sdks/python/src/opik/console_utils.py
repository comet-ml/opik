import logging
from typing import Any, Dict, Mapping, Optional, List, Tuple, TypeVar, Type
import tqdm


LOGGER = logging.getLogger(__name__)


def _in_jupyter_environment() -> bool:
    """
    Check to see if code is running in a Jupyter environment,
    including jupyter notebook, lab, or console.
    """
    try:
        import IPython
    except Exception:
        return False

    ipy = IPython.get_ipython()
    if ipy is None or not hasattr(ipy, "kernel"):
        return False
    else:
        return True


def _in_ipython_environment() -> bool:
    """
    Check to see if code is running in an IPython environment.
    """
    try:
        import IPython
    except Exception:
        return False

    ipy = IPython.get_ipython()
    if ipy is None:
        return False
    else:
        return True


def _in_colab_environment() -> bool:
    """
    Check to see if code is running in Google colab.
    """
    try:
        import IPython
    except Exception:
        return False

    ipy = IPython.get_ipython()
    return "google.colab" in str(ipy)


def get_tqdm():
    """
    Get a tqdm progress bar for your environment.
    """
    if _in_jupyter_environment() or _in_colab_environment():
        return tqdm.tqdm_notebook
    else:
        return tqdm.tqdm
