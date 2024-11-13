from opik import track
from opik import opik_context
import os

def log_x_traces_with_one_span(x: int, project_name: str, name_prefix: str=''):
    os.environ['OPIK_PROJECT_NAME'] = project_name

    @track
    def f2(input: str):
        return 'test output'
    
    @track
    def f1(input: str):
        opik_context.update_current_trace(name=name_prefix+str(i))
        return f2(input)

    for i in range(x):
        f1('test input')