import logging
import sys

LOGGER = logging.getLogger(__name__)


def is_interactive() -> bool:
    """
    Determines if the current environment is interactive.

    Returns:
        bool: True if the environment is either running in a terminal,
              a Jupyter notebook, an IPython environment, or Google Colab.
              False otherwise.
    """
    return (
        sys.stdin.isatty()
        or _in_jupyter_environment()
        or _in_ipython_environment()
        or _in_colab_environment()
    )


def _in_jupyter_environment() -> bool:
    """
    Determine if the current environment is a Jupyter notebook.

    Returns:
        bool: True if running in a Jupyter notebook environment, otherwise False.
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
    Determines if the current environment is an IPython environment.

    Returns:
        bool: True if the code is running in an IPython environment, False otherwise.
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
    Determines if the code is running within a Google Colab environment.

    Returns:
        bool: True if running in Google Colab, False otherwise.
    """
    try:
        import IPython
    except Exception:
        return False

    ipy = IPython.get_ipython()
    return "google.colab" in str(ipy)


def ask_user_for_approval(message: str) -> bool:
    """
    Prompt the user with a message for approval (Y/Yes/N/No).

    Args:
        message (str): The message to display to the user.

    Returns:
        bool: True if the user approves (Y/Yes/empty input), False if the user disapproves (N/No).

    Logs:
        Error when the user input is not recognized.
    """
    while True:
        users_choice = input(message).strip().upper()
        if users_choice in ("Y", "YES", ""):
            return True
        if users_choice in ("N", "NO"):
            return False
        LOGGER.error("Wrong choice. Please try again.")
