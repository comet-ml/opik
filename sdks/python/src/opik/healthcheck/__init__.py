import opik.healthcheck.rich_representation


def run(show_installed_packages: bool = True) -> None:
    """
    Performs a health check of the application, including validation of configuration,
    verification of library installations, and checking the availability of the backend workspace.
    Prints all relevant information to assist in debugging and diagnostics.
    """
    rich_representation.print_header("healthcheck started")

    rich_representation.print_versions()

    if show_installed_packages:
        rich_representation.print_header("libraries installed")
        rich_representation.print_installed_packages()

    # rich_representation.print_header("configuration file")
    # rich_representation.print_config_file_details()
    #
    # rich_representation.print_header("current settings")
    # rich_representation.print_current_config()
    #
    # rich_representation.print_header("current settings validation")
    # rich_representation.print_current_settings_validation()
    #
    # rich_representation.print_header("checking backend workspace availability")
    # rich_representation.print_backend_workspace_availability()
    #
    # rich_representation.print_header("healthcheck completed")
