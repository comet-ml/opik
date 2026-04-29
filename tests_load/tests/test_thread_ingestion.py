import logging
import random
import string
import time
import uuid
from datetime import datetime, timezone

import click
import opik

logging.basicConfig(level=logging.INFO, format="%(levelname)s [%(asctime)s]: %(message)s")

LOGGER = logging.getLogger(__name__)

PROJECT_NAME = "performance_test"


def create_random_string(length: int) -> str:
    return ''.join(random.choices(string.ascii_letters + string.digits, k=length))


def log_threads(num_threads: int, traces_per_thread: int, spans_per_trace: int) -> str | None:
    client = opik.Opik()
    last_thread_id: str | None = None

    for t in range(num_threads):
        thread_id = f"test-thread-{uuid.uuid4().hex[:16]}"
        last_thread_id = thread_id
        now = datetime.now(timezone.utc)

        for i in range(traces_per_thread):
            trace = client.trace(
                name=f"thread-trace-{t}-{i}",
                project_name=PROJECT_NAME,
                input={"message": create_random_string(100), "turn": i},
                output={"response": create_random_string(100)},
                thread_id=thread_id,
                start_time=now,
                end_time=now,
            )

            for s in range(spans_per_trace):
                trace.span(
                    name=f"thread-trace-{t}-{i}-span-{s}",
                    input={"prompt": create_random_string(100)},
                    output={"completion": create_random_string(100)},
                    start_time=now,
                    end_time=now,
                )

    client.flush()
    return last_thread_id


def check_output(thread_id: str):
    opik_client = opik.Opik()
    start_time = time.time()
    while True:
        try:
            threads = opik_client.search_threads(
                project_name=PROJECT_NAME,
                filter_string=f'id = "{thread_id}"',
                max_results=1,
            )
            if threads:
                break
        except Exception as e:
            LOGGER.error(f"There was an exception {e}")
        time.sleep(0.5)

        if time.time() - start_time > 60:
            raise Exception("Timed out waiting for the thread to be available in the UI - Took longer than 60 seconds")


@click.command()
@click.option('--num-threads', default=100, type=click.IntRange(min=0), help='Number of threads to generate')
@click.option('--traces-per-thread', default=10, type=click.IntRange(min=1), help='Number of traces per thread')
@click.option('--spans-per-trace', default=3, type=click.IntRange(min=0), help='Number of spans per trace')
def main(num_threads, traces_per_thread, spans_per_trace):
    start_time = time.time()
    last_thread_id = log_threads(num_threads, traces_per_thread, spans_per_trace)
    end_time_logging = time.time()

    start_time_available_in_ui_check = time.time()
    if last_thread_id is not None:
        check_output(last_thread_id)
    end_time_available_in_ui_check = time.time()

    LOGGER.info("\n---------------- Performance results ----------------")
    LOGGER.info(f"Time to log threads and traces          : {end_time_logging - start_time:.2f} seconds")
    LOGGER.info(
        f"Time before threads are available in UI : {end_time_available_in_ui_check - start_time_available_in_ui_check:.2f} seconds")
    LOGGER.info(f'Total time                              : {time.time() - start_time:.2f} seconds')


if __name__ == "__main__":
    main()
