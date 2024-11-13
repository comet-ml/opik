import pytest
import os
from opik import opik_context, track
from traces_config import PREFIX, PROJECT_NAME


@pytest.fixture(scope='function')
def log_x_traces_with_one_span_via_decorator(traces_number):

    @track
    def f2(input: str):
        return 'test output'
    
    @track
    def f1(input: str):
        opik_context.update_current_trace(name=PREFIX+str(i))
        return f2(input)

    for i in range(traces_number):
        f1('test input')

    yield


@pytest.fixture(scope='function')
def log_x_traces_with_one_span_via_client(client, traces_number):
    for i in range(traces_number):
        client_trace = client.trace(
            name=PREFIX+str(i),
            project_name=PROJECT_NAME,
            input={'input': 'test input'},
            output={'output': 'test output'},
        )
        client_span = client_trace.span(
            name='span',
            input={'input': 'test input'},
            output={'output': 'test output'}
        )
    yield
    

@pytest.fixture(scope='function')
def create_traces(request, traces_number):
    create = request.getfixturevalue(request.param)
    yield 0
