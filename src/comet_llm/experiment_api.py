import json

from typing import Optional, Union, IO
from comet_llm import rest_api_client


class ExperimentAPI:
    def __init__(self, api_key: Optional[str] = None, workspace: Optional[str] = None, project_name: Optional[str] = None):
        self._client = rest_api_client.get(api_key)
        self._initialize_experiment(workspace, project_name)
    
    def _initialize_experiment(self, workspace: Optional[str] = None, project_name: Optional[str] = None) -> None:
        response = self._client.create_experiment(workspace, project_name)
        content = json.loads(response.text)
        self._experiment_key = content["experimentKey"]

    def log_asset(self, file_name: str, file_data: Union[str, IO]) -> None:
        self._client.log_experiment_asset(
            self._experiment_key,
            file_name=file_name,
            file_data=file_data
        )
    
    def stop(self) -> None:
        self._client.stop_experiment(self._experiment_key)