import opik.config


def get_ui_url() -> str:
    config = opik.config.OpikConfig()
    opik_url_override = config.url_override

    return opik_url_override.rstrip("/api")


def get_projects_url() -> str:
    config = opik.config.OpikConfig()
    ui_url = get_ui_url()

    return f"{ui_url}/{config.workspace}/projects"
