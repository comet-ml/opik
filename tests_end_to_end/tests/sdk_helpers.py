import time
import datetime as dt
from opik.rest_api.client import OpikApi
from typing import Optional
import json
import opik
import os


def get_opik_api_client():
    return OpikApi(
        base_url=os.getenv("OPIK_URL_OVERRIDE", None),
        workspace_name=os.getenv("OPIK_WORKSPACE", None),
        api_key=os.getenv("OPIK_API_KEY", None),
    )


def create_project_api(name: str):
    client = get_opik_api_client()
    client.projects.create_project(name=name)


def find_project_by_name_sdk(name: str):
    client = get_opik_api_client()
    proj_page = client.projects.find_projects(name=name, page=1, size=1)
    return proj_page.dict()["content"]


def delete_project_by_name_sdk(name: str):
    client = get_opik_api_client()
    project = find_project_by_name_sdk(name=name)
    client.projects.delete_project_by_id(project[0]["id"])


def wait_for_project_to_be_visible(project_name, timeout=10, initial_delay=1):
    start_time = time.time()
    delay = initial_delay

    while time.time() - start_time < timeout:
        if find_project_by_name_sdk(project_name):
            return

        time.sleep(delay)
        delay = min(delay * 2, timeout - (time.time() - start_time))

    raise TimeoutError(
        f"could not get created project {project_name} via API within {timeout} seconds"
    )


def wait_for_project_to_not_be_visible(project_name, timeout=10, initial_delay=1):
    start_time = time.time()
    delay = initial_delay

    while time.time() - start_time < timeout:
        if not find_project_by_name_sdk(project_name):
            return

        time.sleep(delay)
        delay = min(delay * 2, timeout - (time.time() - start_time))

    raise TimeoutError(
        f"{project_name} has not been deleted via API within {timeout} seconds"
    )


def update_project_by_name_sdk(name: str, new_name: str):
    client = get_opik_api_client()
    wait_for_project_to_be_visible(name, timeout=10)
    projects_match = find_project_by_name_sdk(name)
    project_id = projects_match[0]["id"]

    client.projects.update_project(id=project_id, name=new_name)

    return project_id


def create_traces_sdk(prefix: str, project_name: str, qty: int):
    client = get_opik_api_client()
    for i in range(qty):
        client.traces.create_trace(
            name=prefix + str(i),
            project_name=project_name,
            start_time=dt.datetime.now(),
        )


def wait_for_traces_to_be_visible(project_name, size, timeout=10, initial_delay=1):
    start_time = time.time()
    delay = initial_delay

    while time.time() - start_time < timeout:
        if get_traces_of_project_sdk(project_name=project_name, size=size):
            return

        time.sleep(delay)
        delay = min(delay * 2, timeout - (time.time() - start_time))

    raise TimeoutError(
        f"could not get traces of project {project_name} via API within {timeout} seconds"
    )


def wait_for_number_of_traces_to_be_visible(
    project_name, number_of_traces, timeout=10, initial_delay=1
):
    start_time = time.time()
    delay = initial_delay

    while time.time() - start_time < timeout:
        traces = get_traces_of_project_sdk(
            project_name=project_name, size=number_of_traces
        )
        if len(traces) >= number_of_traces:
            return

        time.sleep(delay)
        delay = min(delay * 2, timeout - (time.time() - start_time))

    raise TimeoutError(
        f"could not get {number_of_traces} traces of project {project_name} via API within {timeout} seconds"
    )


def get_traces_of_project_sdk(project_name: str, size: int):
    client = get_opik_api_client()
    traces = client.traces.get_traces_by_project(project_name=project_name, size=size)
    return traces.dict()["content"]


def delete_list_of_traces_sdk(ids: list[str]):
    client = get_opik_api_client()
    client.traces.delete_traces(ids=ids)


def update_trace_by_id(id: str):
    client = get_opik_api_client()
    client.traces.update_trace(
        id=id,
    )


def get_dataset_by_name(dataset_name: str):
    client = get_opik_api_client()
    dataset = client.datasets.get_dataset_by_identifier(dataset_name=dataset_name)
    return dataset.dict()


def update_dataset_name(name: str, new_name: str):
    client = get_opik_api_client()
    dataset = get_dataset_by_name(dataset_name=name)
    dataset_id = dataset["id"]

    dataset = client.datasets.update_dataset(id=dataset_id, name=new_name)

    return dataset_id


def delete_dataset_by_name_if_exists(dataset_name: str):
    client = get_opik_api_client()
    dataset = None
    try:
        dataset = get_dataset_by_name(dataset_name)
    except Exception as _:
        print(f"Trying to delete dataset {dataset_name}, but it does not exist")
    finally:
        if dataset:
            client.datasets.delete_dataset_by_name(dataset_name=dataset_name)


def get_experiment_by_id(exp_id: str):
    client = get_opik_api_client()
    exp = client.experiments.get_experiment_by_id(exp_id)
    return exp


def delete_experiment_by_id(exp_id: str):
    client = get_opik_api_client()
    client.experiments.delete_experiments_by_id(ids=[exp_id])


def delete_experiment_items_by_id(ids: list[str]):
    client = get_opik_api_client()
    client.experiments.delete_experiment_items(ids=ids)


def experiment_items_stream(exp_name: str, limit: Optional[int] = None):
    client = get_opik_api_client()
    data = b"".join(
        client.experiments.stream_experiment_items(
            experiment_name=exp_name, request_options={"chunk_size": 100}
        )
    )
    lines = data.decode("utf-8").split("\r\n")
    dict_list = [json.loads(line) for line in lines if line.strip()]
    return dict_list


def client_get_prompt_retries(
    client: opik.Opik, prompt_name, timeout=10, initial_delay=1
):
    start_time = time.time()
    delay = initial_delay
    time.sleep(initial_delay)

    while time.time() - start_time < timeout:
        prompt = client.get_prompt(name=prompt_name)

        if prompt:
            return prompt

        time.sleep(delay)
        delay = min(delay * 2, timeout - (time.time() - start_time))

    raise TimeoutError(
        f"could not get created prompt {prompt_name} via SDK client within {timeout} seconds"
    )
