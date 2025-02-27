import opik
from opik import config, environment
from opik.healthcheck import checks, rich_representation


def run(show_installed_packages: bool = True) -> None:
    """
    Performs a health check of the application, including validation of configuration,
    verification of library installations, and checking the availability of the backend workspace.
    Prints all relevant information to assist in debugging and diagnostics.
    """
    rich_representation.print_header("healthcheck started")

    python_version = environment.get_python_version()
    opik_version = opik.__version__

    rich_representation.print_versions(python_version, opik_version)

    if show_installed_packages:
        rich_representation.print_header("packages installed")
        installed_packages = environment.get_installed_packages()
        rich_representation.print_installed_packages(installed_packages)

    config_obj = config.OpikConfig()

    rich_representation.print_header("configuration file")
    rich_representation.print_config_file_details(config_obj)

    rich_representation.print_header("current configuration")
    rich_representation.print_current_config(config_obj)

    if config_obj.is_cloud_installation or config_obj.is_localhost_installation:
        # Config can be validated ONLY for localhost and cloud, not for other installations
        rich_representation.print_header("current configuration validation")
        is_misconfigured, err_msg = config_obj.get_misconfiguration_validation_results()
        rich_representation.print_config_validation(not is_misconfigured, err_msg)

    rich_representation.print_header("backend workspace availability")
    is_available, err_msg = checks.get_backend_workspace_availability()
    rich_representation.print_backend_workspace_availability(is_available, err_msg)

    rich_representation.print_header("healthcheck completed")
