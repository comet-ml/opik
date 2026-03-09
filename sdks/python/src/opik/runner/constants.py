import os


def opik_home() -> str:
    """Root directory for Opik local state (~/.opik by default, override with OPIK_HOME)."""
    return os.environ.get("OPIK_HOME", os.path.expanduser("~/.opik"))


def agents_file() -> str:
    """Path to the agents registry file."""
    return os.path.join(opik_home(), "agents.json")


def runner_state_file() -> str:
    """Path to the file tracking the active runner process."""
    return os.path.join(opik_home(), "runner.json")


def ensure_opik_home() -> None:
    """Create the Opik home directory if it doesn't exist."""
    os.makedirs(opik_home(), exist_ok=True)
