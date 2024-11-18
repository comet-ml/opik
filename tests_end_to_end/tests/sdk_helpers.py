import pytest
import os
import time
import datetime as dt
import opik
from playwright.sync_api import Page
from opik.rest_api.client import OpikApi
from opik import track


def create_project_sdk(name: str):
    client = OpikApi()
    client.projects.create_project(name=name)


def find_project_by_name_sdk(name: str):
    client = OpikApi()
    proj_page = client.projects.find_projects(name=name, page=1, size=1)
    return proj_page.dict()['content']


def delete_project_by_name_sdk(name: str):
    client = OpikApi()
    project = find_project_by_name_sdk(name=name)
    client.projects.delete_project_by_id(project[0]['id'])


def wait_for_project_to_be_visible(project_name, timeout=10, initial_delay=1):
    start_time = time.time()
    delay = initial_delay
    
    while time.time() - start_time < timeout:
        if find_project_by_name_sdk(project_name):
            return True
        
        time.sleep(delay)
        delay = min(delay*2, timeout-(time.time() - start_time))
    
    raise TimeoutError(f'could not get created project {project_name} via API within {timeout} seconds')


def update_project_by_name_sdk(name: str, new_name: str):
    client = OpikApi()
    wait_for_project_to_be_visible(name, timeout=10)
    projects_match = find_project_by_name_sdk(name)
    project_id = projects_match[0]['id']

    client.projects.update_project(id=project_id, name=new_name)

    return project_id


def create_traces_sdk(prefix: str, project_name: str, qty: int):
    client = OpikApi()
    for i in range(qty):
        client.traces.create_trace(
            name=prefix+str(i),
            project_name=project_name,
            start_time=dt.datetime.now()
        )


def wait_for_traces_to_be_visible(project_name, size, timeout=10, initial_delay=1):
    start_time = time.time()
    delay = initial_delay
    
    while time.time() - start_time < timeout:
        if get_traces_of_project_sdk(project_name=project_name, size=size):
            return True
        
        time.sleep(delay)
        delay = min(delay*2, timeout-(time.time() - start_time))
    
    raise TimeoutError(f'could not get traces of project {project_name} via API within {timeout} seconds')


def wait_for_number_of_traces_to_be_visible(project_name, number_of_traces, timeout=10, initial_delay=1):
    start_time = time.time()
    delay = initial_delay
    
    while time.time() - start_time < timeout:
        traces = get_traces_of_project_sdk(project_name=project_name, size=number_of_traces)
        if len(traces) >= number_of_traces:
            return True
        
        time.sleep(delay)
        delay = min(delay*2, timeout-(time.time() - start_time))
    
    raise TimeoutError(f'could not get {number_of_traces} traces of project {project_name} via API within {timeout} seconds')

def get_traces_of_project_sdk(project_name: str, size: int):
    client = OpikApi()
    traces = client.traces.get_traces_by_project(project_name=project_name, size=size)
    return traces.dict()['content']


def delete_list_of_traces_sdk(ids: list[str]):
    client = OpikApi()
    client.traces.delete_traces(ids=ids)


def update_trace_by_id(id: str):
    client = OpikApi()
    client.traces.update_trace(id=id, )