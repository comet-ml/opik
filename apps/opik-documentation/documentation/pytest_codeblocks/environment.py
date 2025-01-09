from typing import Optional, List
import os
import tempfile
import subprocess
import venv
from datetime import datetime


def setup_env(packages: Optional[List[str]] = None):
    """
    Create a virtual environment and install required packages.

    Args:
        packages: Optional list of packages to install in the environment
    """
    # Create a virtual environment
    env_path = os.path.join(
        tempfile.gettempdir(), f"venv_{datetime.now().strftime('%Y%m%d%H%M%S')}"
    )
    venv.create(env_path, with_pip=True, clear=True)

    # Get paths to executables
    if os.name == "nt":  # Windows
        python_path = os.path.join(env_path, "Scripts", "python.exe")
        pip_path = os.path.join(env_path, "Scripts", "pip.exe")
    else:  # Unix-like
        python_path = os.path.join(env_path, "bin", "python")
        pip_path = os.path.join(env_path, "bin", "pip")

    # Install required packages
    if packages:
        subprocess.run(
            [pip_path, "install"] + packages, capture_output=True, check=True
        )

    return env_path, python_path, pip_path
