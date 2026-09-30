import enum
import logging
import sys
from typing import Optional

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


def ask_user_for_approval(question: str) -> bool:
    """Ask a yes/no question that Enter answers yes.

    ``question`` is the question alone. The ``[Y/n]`` and the indent are added
    here so that this and the CLI's ``install_view.confirm_default_yes`` put the
    same shape on screen — `opik configure` runs both, and each call site used
    to spell its own suffix, which is how ``(Y/n)`` with the cursor jammed
    against the bracket ended up next to ``[Y/n]: ``.

    Plain ``input`` rather than ``click`` or ``rich``: this half is reachable
    from ``opik.configure()``, which is a library call and must not take over
    someone's stdout.
    """
    while True:
        answer = input(f"  {question} [Y/n]: ").strip().upper()
        if answer in ("Y", "YES", ""):
            return True
        if answer in ("N", "NO"):
            return False
        LOGGER.error("Wrong choice. Please try again.")


class DeploymentType(enum.Enum):
    CLOUD = (1, "Opik Cloud (default)")
    SELF_HOSTED = (2, "Self-hosted Comet platform")
    LOCAL = (3, "Local deployment")

    @classmethod
    def find_by_value(cls, value: int) -> "DeploymentType":
        """
        Find the DeploymentType by its integer value.

        :param value: The integer value of the DeploymentType.
        :return: The corresponding DeploymentType.
        """
        for v in cls:
            if v.value[0] == value:
                return v
        raise ValueError(f"No DeploymentType with value '{value}'")


def ask_user_for_deployment_type(prompt: Optional[str] = None) -> DeploymentType:
    """
    Asks the user to select a deployment type from the available Opik deployment options.
    Prompts the user until a valid selection is made.

    ``prompt`` replaces the question this would otherwise print, for a caller that
    has already rendered one — the CLI does, in colour. Only the wording moves:
    the reading stays here, so every caller accepts the same answers (``1``,
    ``2``, ``3``, or Enter for the default) and anything piping them in is
    unaffected by how the question looked.

    Returns:
        DeploymentType: The user's selected deployment type.
    """
    if prompt is not None:
        message_string = prompt
    else:
        msg = ["Which Opik deployment do you want to log your traces to?"]

        for deployment in DeploymentType:
            msg.append(f"{deployment.value[0]} - {deployment.value[1]}")

        msg.append("\n> ")

        message_string = "\n".join(msg)

    while True:
        choice_str = input(message_string).strip()

        if choice_str not in ("1", "2", "3", ""):
            LOGGER.error("Wrong choice. Please try again.\n")
            continue

        if choice_str == "":
            choice_index = 1
        else:
            choice_index = int(choice_str)

        choice = DeploymentType.find_by_value(choice_index)

        return choice
