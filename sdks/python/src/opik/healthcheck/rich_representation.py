from typing import Dict, Optional

from rich import align
from rich.console import Console
from rich.table import Table
from rich.text import Text

from opik import config
from opik.config import OpikConfig

DEFAULT_KEY_COLOR = "green"
DEFAULT_VALUE_COLOR = "blue"
DEFAULT_ERROR_COLOR = "red"

console = Console()


def make_key_text(key: str) -> Text:
    return Text(key, style=DEFAULT_KEY_COLOR)


def make_value_text(value: str) -> Text:
    return Text(value, style=DEFAULT_VALUE_COLOR)


def print_header(text: str) -> None:
    header_text = f"*** {text.upper()} ***"
    header_text = Text(header_text, style="bold magenta")
    header_text = align.Align.left(header_text)

    console.print()
    console.print(header_text)


def print_installed_packages(packages: Dict[str, str]) -> None:
    for name, version in sorted(packages.items()):
        name = make_key_text(name)
        version = make_value_text(version)

        console.print(name, "==", version, sep="")


def print_versions(python_version: str, opik_version: str) -> None:
    python_version_label = make_key_text("Python version:")
    python_version = make_value_text(python_version)
    opik_version_label = make_key_text("Opik version:")
    opik_version = make_value_text(opik_version)

    console.print(python_version_label, python_version)
    console.print(opik_version_label, opik_version)


def print_config_file_details(config: OpikConfig) -> None:
    file_path_label = make_key_text("Config file path:")
    file_path = make_value_text(str(config.config_file_fullpath))

    is_exists_label = make_key_text("Config file exists:")
    is_exists = make_value_text(str(config.is_config_file_exists))

    console.print(file_path_label, file_path)
    console.print(is_exists_label, is_exists)


def print_current_config(config: config.OpikConfig) -> None:
    table = Table(show_header=True, header_style="bold magenta")
    table.add_column("Setting", style=DEFAULT_KEY_COLOR)
    table.add_column("Value", style=DEFAULT_VALUE_COLOR)

    current_config_values = config.get_current_config_with_api_key_hidden()
    for key, value in sorted(current_config_values.items()):
        if key != "sentry_dsn":
            table.add_row(key, str(value))

    console.print(table)


def print_config_validation(is_valid: bool, error_message: Optional[str]) -> None:
    is_valid_text = Text(
        str(is_valid), style=DEFAULT_VALUE_COLOR if is_valid else DEFAULT_ERROR_COLOR
    )
    is_valid_label = make_key_text("Current configuration is valid:")

    console.print(is_valid_label, is_valid_text)

    if is_valid:
        return

    err_msg = Text(error_message, style=DEFAULT_ERROR_COLOR)
    console.print(err_msg)


def print_backend_workspace_availability(
    is_available: bool,
    err_msg: Optional[str],
) -> None:
    is_available_text = Text(
        str(is_available),
        style=DEFAULT_VALUE_COLOR if is_available else DEFAULT_ERROR_COLOR,
    )
    is_available_label = make_key_text("Backend workspace available:")

    console.print(is_available_label, is_available_text)

    if err_msg:
        err_msg = Text(err_msg, style=DEFAULT_ERROR_COLOR)
        console.print(err_msg)
