import opik.config
import urllib.parse


def get_ui_url() -> str:
    config = opik.config.OpikConfig()
    opik_url_override = config.url_override

    parsed_url = urllib.parse.urlparse(opik_url_override)
    return f"{parsed_url.scheme}://{parsed_url.netloc}"
