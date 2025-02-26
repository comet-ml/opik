from typing import Optional, Tuple

import httpx

from opik import Opik


def get_backend_workspace_availability() -> Tuple[bool, Optional[str]]:
    is_available = False
    err_msg = None

    try:
        opik_obj = Opik(_show_misconfiguration_message=False)
        opik_obj.auth_check()
        is_available = True
    except httpx.ConnectError as e:
        err_msg = (
            f"Error while checking backend workspace availability: {e}\n\n"
            "Can't connect to the backend service. If you are using local Opik deployment, "
            "please check https://www.comet.com/docs/opik/self-host/local_deployment\n\n"
            "If you are using cloud version - please check your internet connection."
        )
    except Exception as e:
        err_msg = f"Error while checking backend workspace availability: {e}"

    return is_available, err_msg
