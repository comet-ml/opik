import pathlib
from typing import Optional, Tuple

import httpx

from opik import Opik, config


def get_backend_workspace_availability(config: config.OpikConfig) -> Tuple[bool, Optional[str]]:
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


def get_config_validation_results(config_: config.OpikConfig) -> Tuple[bool, Optional[str]]:
    is_valid = not config.is_misconfigured(config_, False)
    if is_valid:
        return True, None

    is_misconfigured_for_cloud_flag, error_message = config.is_misconfigured_for_cloud(
        config_
    )
    if is_misconfigured_for_cloud_flag:
        return False, error_message

    is_misconfigured_for_local_flag, error_message = config.is_misconfigured_for_local(
        config_
    )
    if is_misconfigured_for_local_flag:
        return False, error_message

    return False, "Unknown error"
