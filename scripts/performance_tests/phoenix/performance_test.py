import os
import uuid
import time
import random
import string
import logging
import phoenix
from phoenix.trace.dsl import SpanQuery
from opentelemetry import trace as trace_api
from phoenix.otel import register

logging.basicConfig(
    level=logging.WARNING,
    format='%(levelname)s: %(message)s'
)

LOGGER = logging.getLogger(__name__)

# Setup up the Phoenix tracer
tracer_provider = register(
    project_name="performance_test",
)

tracer = trace_api.get_tracer(__name__)

def create_random_string(length: int) -> str:
    return ''.join(random.choices(string.ascii_letters + string.digits, k=length))

@tracer.start_as_current_span("create_span")
def create_span(input: str) -> str:
    current_span = trace_api.get_current_span()
    current_span.set_attribute("input.input", input)

    output = create_random_string(100)
    current_span.set_attribute("output", output)
    return output

@tracer.start_as_current_span("create_trace")
def create_trace(input: str, unique_id: str) -> str:
    current_span = trace_api.get_current_span()
    current_span.set_attribute("input.input", input)
    current_span.set_attribute("input.unique_id", unique_id)

    for i in range(3):
        input = create_random_string(100)
        create_span(input)

    output = create_random_string(100)
    current_span.set_attribute("output", output)
    return output

def log_traces_and_spans(nb_traces: int) -> str:
    for i in range(nb_traces):
        uuid_str = str(uuid.uuid4())
        create_trace(create_random_string(100), unique_id=uuid_str)
    
    tracer_provider.force_flush()
    return uuid_str

def check_output(unique_id: str):
    phoenix_client = phoenix.Client()

    start_time = time.time()
    while True:
        data_retrieval_start_time = time.time()
        matching_traces = phoenix_client.query_spans(
            SpanQuery().where(
                f"'{unique_id}' in input.unique_id"
            ),
            project_name="performance_test"
        )
        project_search_time = time.time() - data_retrieval_start_time
        if project_search_time > 0.5:
            LOGGER.warning(f"Checking if the trace has been logged took over 0.5 seconds: {project_search_time:.2f} seconds")
        if len(matching_traces) > 0:
            break
        else:
            time.sleep(0.5)
        
        if time.time() - start_time > 600:
            raise Exception("Timed out waiting for the trace to be available in the UI - Took longer than 600 seconds")

if __name__ == "__main__":
    import click

@click.command()
@click.option('--num-traces', default=1000, help='Number of traces to generate')
def main(num_traces):
    start_time = time.time()
    last_trace_unique_id = log_traces_and_spans(num_traces)
    end_time_logging = time.time()
    
    start_time_available_in_ui_check = time.time()
    check_output(last_trace_unique_id)
    end_time_available_in_ui_check = time.time()

    print("\n---------------- Performance results ----------------")
    print(f"Time to log traces and spans           : {end_time_logging - start_time:.2f} seconds")
    print(f"Time before traces are available in UI : {end_time_available_in_ui_check - start_time_available_in_ui_check:.2f} seconds")
    print(f'Total time                             : {time.time() - start_time:.2f} seconds')

if __name__ == "__main__":
    main()
