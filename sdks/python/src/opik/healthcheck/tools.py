from pathlib import Path
from typing import Any, Dict, Optional, Tuple
from unittest.mock import patch

from httpx import ConnectError
from rich import align
from rich.console import Console
from rich.table import Table
from rich.text import Text

import opik
from opik import Opik, config, environment

console = Console()

DEFAULT_KEY_COLOR = "green"
DEFAULT_VALUE_COLOR = "blue"
DEFAULT_ERROR_COLOR = "red"


def print_header(text: str) -> None:
    header_text = f"*** {text.upper()} ***"
    header_text = Text(header_text, style="bold magenta")
    header_text = align.Align.left(header_text)

    console.print()
    console.print(header_text)


def print_installed_packages() -> None:
    for name, version in sorted(environment.get_installed_packages().items()):
        name = Text(name, style=DEFAULT_KEY_COLOR)
        version = Text(version, style=DEFAULT_VALUE_COLOR)

        console.print(name, "==", version, sep="")


def print_version() -> None:
    python_version = environment.get_python_version()
    python_version_label = Text("Python version:", style=DEFAULT_KEY_COLOR)
    python_version = Text(python_version, style=DEFAULT_VALUE_COLOR)
    opik_version_label = Text("Opik version:", style=DEFAULT_KEY_COLOR)
    opik_version = Text(opik.__version__, style=DEFAULT_VALUE_COLOR)

    console.print(python_version_label, python_version)
    console.print(opik_version_label, opik_version)


def get_config_file_details() -> Tuple[Path, bool]:
    config_obj = config.OpikConfig()
    return config_obj.config_file_fullpath, config_obj.is_config_file_exists


def print_config_file_details() -> None:
    file_path, is_exists = get_config_file_details()

    file_path_label = Text("Config file path:", style=DEFAULT_KEY_COLOR)
    file_path = Text(str(file_path), style=DEFAULT_VALUE_COLOR)
    is_exists_label = Text("Config file exists:", style=DEFAULT_KEY_COLOR)
    is_exists = Text(str(is_exists), style=DEFAULT_VALUE_COLOR)

    console.print(file_path_label, file_path)
    console.print(is_exists_label, is_exists)


def get_current_settings() -> Dict[str, Any]:
    config_obj = config.OpikConfig()
    settings = config_obj.model_dump()
    if settings.get("api_key") is not None:
        settings["api_key"] = "*** HIDDEN ***"
    return settings


def print_current_settings() -> None:
    current_settings = get_current_settings()
    table = Table(show_header=True, header_style="bold magenta")
    table.add_column("Setting", style=DEFAULT_KEY_COLOR)
    table.add_column("Value", style=DEFAULT_VALUE_COLOR)

    for key, value in sorted(current_settings.items()):
        table.add_row(key, str(value))

    console.print(table)


def print_current_settings_validation() -> None:
    config_obj = config.OpikConfig()

    is_valid = not config.is_misconfigured(config_obj, False)
    is_valid_text = Text(
        str(is_valid), style=DEFAULT_VALUE_COLOR if is_valid else DEFAULT_ERROR_COLOR
    )
    is_valid_label = Text("Current configuration is valid:", style=DEFAULT_KEY_COLOR)

    console.print(is_valid_label, is_valid_text)

    if is_valid:
        return

    is_misconfigured_for_cloud_flag, error_message = config.is_misconfigured_for_cloud(
        config_obj
    )
    if is_misconfigured_for_cloud_flag:
        err_msg = Text(error_message, style="red")
        console.print(err_msg)
        return

    is_misconfigured_for_local_flag, error_message = config.is_misconfigured_for_local(
        config_obj
    )
    if is_misconfigured_for_local_flag:
        err_msg = Text(error_message, style="red")
        console.print(err_msg)
        return


def get_backend_workspace_availability() -> Tuple[bool, Optional[str]]:
    is_available = False
    err_msg = None

    try:
        # supress showing configuration errors
        with patch("opik.config.is_misconfigured", lambda *args, **kwargs: False):
            opik = Opik()

        opik.auth_check()
        is_available = True
    except ConnectError as e:
        err_msg = (
            f"Error while checking backend workspace availability: {e}\n\n"
            "Can't connect to the backend service. If you are using local Opik deployment, "
            "please check https://www.comet.com/docs/opik/self-host/local_deployment\n\n"
            "If you are using cloud version - please check your internet connection."
        )
    except Exception as e:
        err_msg = f"Error while checking backend workspace availability: {e}"

    return is_available, err_msg


def print_backend_workspace_availability() -> None:
    is_available, err_msg = get_backend_workspace_availability()

    is_available_text = Text(
        str(is_available),
        style=DEFAULT_VALUE_COLOR if is_available else DEFAULT_ERROR_COLOR,
    )
    is_available_label = Text("Backend workspace available:", style=DEFAULT_KEY_COLOR)

    console.print(is_available_label, is_available_text)

    if err_msg:
        err_msg = Text(err_msg, style="red")
        console.print(err_msg)
