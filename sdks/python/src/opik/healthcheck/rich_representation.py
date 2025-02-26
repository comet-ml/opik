from typing import Optional

from rich import align
from rich.console import Console
from rich.table import Table
from rich.text import Text

from opik import config, environment
from opik.config import OpikConfig

DEFAULT_KEY_COLOR = "green"
DEFAULT_VALUE_COLOR = "blue"
DEFAULT_ERROR_COLOR = "red"

console = Console()


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


def print_versions(python_version: str, opik_version: str) -> None:
    python_version_label = Text("Python version:", style=DEFAULT_KEY_COLOR)
    python_version = Text(python_version, style=DEFAULT_VALUE_COLOR)
    opik_version_label = Text("Opik version:", style=DEFAULT_KEY_COLOR)
    opik_version = Text(opik_version, style=DEFAULT_VALUE_COLOR)

    console.print(python_version_label, python_version)
    console.print(opik_version_label, opik_version)


def print_config_file_details(config: OpikConfig) -> None:
    file_path_label = Text("Config file path:", style=DEFAULT_KEY_COLOR)
    file_path = Text(str(config.config_file_fullpath), style=DEFAULT_VALUE_COLOR)
    is_exists_label = Text("Config file exists:", style=DEFAULT_KEY_COLOR)
    is_exists = Text(str(config.is_config_file_exists), style=DEFAULT_VALUE_COLOR)

    console.print(file_path_label, file_path)
    console.print(is_exists_label, is_exists)


def print_current_config(config: config.OpikConfig) -> None:
    current_config_values = config.get_current_config_with_api_key_hidden()
    table = Table(show_header=True, header_style="bold magenta")
    table.add_column("Setting", style=DEFAULT_KEY_COLOR)
    table.add_column("Value", style=DEFAULT_VALUE_COLOR)

    for key, value in sorted(current_config_values.items()):
        table.add_row(key, str(value))

    console.print(table)


def print_config_validation(is_valid: bool, error_message: Optional[str]) -> None:
    is_valid_text = Text(
        str(is_valid), style=DEFAULT_VALUE_COLOR if is_valid else DEFAULT_ERROR_COLOR
    )
    is_valid_label = Text("Current configuration is valid:", style=DEFAULT_KEY_COLOR)

    console.print(is_valid_label, is_valid_text)

    if is_valid:
        return

    err_msg = Text(error_message, style="red")
    console.print(err_msg)


def print_backend_workspace_availability(is_available: bool, err_msg: Optional[str]) -> None:
    is_available_text = Text(
        str(is_available),
        style=DEFAULT_VALUE_COLOR if is_available else DEFAULT_ERROR_COLOR,
    )
    is_available_label = Text("Backend workspace available:", style=DEFAULT_KEY_COLOR)

    console.print(is_available_label, is_available_text)

    if err_msg:
        err_msg = Text(err_msg, style="red")
        console.print(err_msg)
