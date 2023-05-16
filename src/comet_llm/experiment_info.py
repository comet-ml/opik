
from . import config 


# class ExperimentInfo:
#     api_key
#     workspace
#     project_name

def get_experiment_info(
        api_key=None,
        workspace=None,
        project_name=None,
        raise_if_api_key_not_found=None
    ):
    api_key = api_key if api_key else config.api_key()
    workspace = workspace if workspace else config.workspace()
    project_name = project_name if project_name else config.project_name()
    
