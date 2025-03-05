from typing import Optional, Tuple

import httpx
from rich.console import Console

from opik import Opik

console = Console()


def get_backend_workspace_availability() -> Tuple[bool, Optional[str]]:
    is_available = False
    err_msg = None

    opik_obj = Opik(_show_misconfiguration_message=False)
    console.print(
        f"--> Checking backend workspace availability at: {opik_obj.config.url_override}"
    )

    try:
        opik_obj.auth_check()
        is_available = True
    except (httpx.ConnectError, httpx.TimeoutException) as e:
        err_msg = (
            f"Error while checking backend workspace availability: {e}\n\n"
            "Can't connect to the backend service. "
        )

        if opik_obj.config.is_cloud_installation:
            err_msg += "Please check your internet connection."
        else:
            err_msg += "Please check https://www.comet.com/docs/opik/self-host/local_deployment\n\n"

    except Exception as e:
        err_msg = f"Error while checking backend workspace availability: {e}"

    return is_available, err_msg
