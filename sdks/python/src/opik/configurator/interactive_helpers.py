import enum
import sys
from typing import Optional


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

    Adds the indent and ``[Y/n]`` itself, matching the CLI's prompts. Plain
    ``input``: this is reachable from ``opik.configure()``, a library call.
    """
    while True:
        answer = input(f"  {question} [Y/n]: ").strip().upper()
        if answer in ("Y", "YES", ""):
            return True
        if answer in ("N", "NO"):
            return False
        # Printed like the prompt, not logged as an `OPIK:` line.
        print("  Please answer y or n.")


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


#: Shared by `opik configure` and `opik.configure()`, so both word it the same.
DEPLOYMENT_QUESTION = "Where should Opik log your traces?"

#: What follows the options, wherever they were drawn.
DEPLOYMENT_ANSWER_PROMPT = "\n  Enter 1, 2 or 3 [1]: "


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
        msg = [DEPLOYMENT_QUESTION]

        for deployment in DeploymentType:
            msg.append(f"{deployment.value[0]} - {deployment.value[1]}")

        msg.append(DEPLOYMENT_ANSWER_PROMPT)

        message_string = "\n".join(msg)

    while True:
        choice_str = input(message_string).strip()

        if choice_str not in ("1", "2", "3", ""):
            print("  Please enter 1, 2 or 3.")
            continue

        if choice_str == "":
            choice_index = 1
        else:
            choice_index = int(choice_str)

        choice = DeploymentType.find_by_value(choice_index)

        return choice
