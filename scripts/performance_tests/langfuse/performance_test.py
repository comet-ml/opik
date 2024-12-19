import os

os.environ["LANGFUSE_SECRET_KEY"] = "sk-lf-e3eb26a2-ff5f-4995-9526-08ba46ea5b61"
os.environ["LANGFUSE_PUBLIC_KEY"] = "pk-lf-cdee602d-ff4d-4609-8bd0-904628e9c930"
os.environ["LANGFUSE_HOST"] = "http://localhost:3000"

import uuid
import langfuse
from langfuse.decorators import observe, langfuse_context
import time
import random
import string
import logging

logging.basicConfig(
    level=logging.CRITICAL,
    format='%(levelname)s: %(message)s'
)

LOGGER = logging.getLogger(__name__)


def create_random_string(length: int) -> str:
    return ''.join(random.choices(string.ascii_letters + string.digits, k=length))

@observe
def create_span(input: str) -> str:
    output = create_random_string(100)
    return output

@observe
def create_trace(input: str) -> str:

    for i in range(3):
        input = create_random_string(100)
        create_span(input)
    
    return create_random_string(100), langfuse_context.get_current_trace_id()

def log_traces_and_spans(nb_traces: int) -> str:
    for i in range(nb_traces):
        _, trace_id = create_trace(create_random_string(100))
    
    langfuse_context.flush()
    return trace_id

def check_output(trace_id: str):
    langfuse_client = langfuse.Langfuse()
    print("Start checking output")

    start_time = time.time()
    while True:
        try:
            langfuse_client.fetch_trace(trace_id)
            break
        except Exception as e:
            time.sleep(0.5)
        
        if time.time() - start_time > 600:
            raise Exception("Timed out waiting for the trace to be available in the UI - Took longer than 60 seconds")

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
