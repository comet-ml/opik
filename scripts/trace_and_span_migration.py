"""
Downloading traces: 25000 traces [00:30, 818.92 traces/s]
Downloading spans: 25000 spans [00:59, 418.66 spans/s]

"""

import logging
import os
import pickle
import time
from typing import List

import tqdm

from opik import Opik
from opik.api_objects import span, trace
from opik.api_objects.trace import migration

logging.basicConfig(
    # level=logging.DEBUG,
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(message)s"
)
LOGGER = logging.getLogger(__name__)

PROJECT_NAME = ""
DESTINATION_PROJECT_NAME = "TEST_PROJECT_MIGRATION"
API_KEY = ""
WORKSPACE = ""
MAX_RESULTS_BATCH_SIZE = 1_000
MAX_RETRIES = 3

DATA_DIR = "./span_data/"
CONVERTED_DATA_DIR = "./span_data_converted/"

VERBOSE = 1
MAX_RESULTS = 25_000
SPAN_SAVE_BATCH_SIZE = 5_000

os.makedirs(DATA_DIR, exist_ok=True)
os.makedirs(CONVERTED_DATA_DIR, exist_ok=True)

LOGGER.debug("Directories for data and converted data created or already exist.")

client = Opik(
    workspace=WORKSPACE,
    api_key=API_KEY,
    _use_batching=True
)

my_client = Opik(
    workspace="",
    api_key="",
    _use_batching=True
)


def download_traces(client: Opik):
    trace_count = 0
    traces = []
    page = 1

    pbar = tqdm.tqdm(desc="Downloading traces", unit=" traces")

    while trace_count < MAX_RESULTS:
        retries = 0
        while retries < MAX_RETRIES:
            try:
                page_traces = client._rest_client.traces.get_traces_by_project(
                    project_name=PROJECT_NAME,
                    page=page,
                    size=MAX_RESULTS_BATCH_SIZE,
                    truncate=False,
                )
                break
            except Exception as e:
                print(f"Got an error - page {page} - {e}")
                retries += 1
                time.sleep(5)

        if len(page_traces.content) == 0:
            break

        traces.extend(page_traces.content)
        trace_count += len(page_traces.content)

        pbar.update(len(page_traces.content))

        if len(traces) >= SPAN_SAVE_BATCH_SIZE:
            with open(f"{DATA_DIR}/traces_{page:04d}.pkl", "wb") as f:
                pickle.dump(traces, f)
            traces = []

        page += 1

    # Save leftovers
    if len(traces) > 0:
        with open(f"{DATA_DIR}/traces_{page + 1:04d}.pkl", "wb") as f:
            pickle.dump(traces, f)

    pbar.close()
    LOGGER.debug(f"Downloaded {trace_count} traces.")


def download_spans(client: Opik):
    span_count = 0
    spans = []
    page = 1

    pbar = tqdm.tqdm(desc="Downloading spans", unit=" spans")

    while span_count < MAX_RESULTS:
        retries = 0
        while retries < MAX_RETRIES:
            try:
                page_spans = client._rest_client.spans.get_spans_by_project(
                    project_name=PROJECT_NAME,
                    page=page,
                    size=MAX_RESULTS_BATCH_SIZE,
                    truncate=False,
                )
                break
            except Exception as e:
                LOGGER.debug(f"Got an error - page {page} - {e}")
                retries += 1
                time.sleep(5)

        if len(page_spans.content) == 0:
            break

        spans.extend(page_spans.content)
        span_count += len(page_spans.content)

        pbar.update(len(page_spans.content))

        if len(spans) >= SPAN_SAVE_BATCH_SIZE:
            with open(f"{DATA_DIR}/spans_{page:04d}.pkl", "wb") as f:
                pickle.dump(spans, f)
            spans = []

        page += 1

    # Save leftovers
    if len(spans) > 0:
        with open(f"{DATA_DIR}/spans_{page + 1:04d}.pkl", "wb") as f:
            pickle.dump(spans, f)

    pbar.close()
    LOGGER.debug(f"Downloaded {span_count} spans.")


def convert_traces() -> List[trace.TraceData]:
    trace_files = [f for f in os.listdir(DATA_DIR) if f.startswith("traces_") and f.endswith(".pkl")]
    trace_files = sorted(trace_files)

    all_trace_data = []

    for trace_file in trace_files:
        file_path = os.path.join(DATA_DIR, trace_file)
        with open(file_path, "rb") as f:
            traces_public = pickle.load(f)

        trace_data = [
            trace.trace_public_to_trace_data(project_name=PROJECT_NAME, trace_public=trace_public_)
            for trace_public_ in traces_public
        ]

        all_trace_data.extend(trace_data)

        # converted_file_path = pathlib.Path(CONVERTED_DATA_DIR) / pathlib.Path(file_path).name
        # with open(converted_file_path, "wb") as f:
        #     pickle.dump(trace_data, f)

    return all_trace_data


def convert_spans():
    span_files = [f for f in os.listdir(DATA_DIR) if f.startswith("spans_") and f.endswith(".pkl")]
    span_files = sorted(span_files)

    all_span_data = []

    for span_file in span_files:
        file_path = os.path.join(DATA_DIR, span_file)
        with open(file_path, "rb") as f:
            spans_public = pickle.load(f)

        span_data = [
            span.span_public_to_span_data(project_name=PROJECT_NAME, span_public_=span_public_)
            for span_public_ in spans_public
        ]

        all_span_data.extend(span_data)

        # converted_file_path = pathlib.Path(CONVERTED_DATA_DIR) / pathlib.Path(file_path).name
        # with open(converted_file_path, "wb") as f:
        #     pickle.dump(span_data, f)

    return all_span_data


def prepare_data_for_copy(trace_data, span_data) -> tuple[List[trace.TraceData], List[span.SpanData]]:
    new_trace_data, new_span_data = (
        migration.prepare_traces_and_spans_for_copy(DESTINATION_PROJECT_NAME, trace_data, span_data)
    )

    return new_trace_data, new_span_data


def upload_traces(client: Opik, trace_data: List[trace.TraceData]):
    pbar = tqdm.tqdm(desc="Uploading traces", unit=" traces", total=len(trace_data))
    for trace_data_ in trace_data:
        client.trace(**trace_data_.__dict__)
        pbar.update(1)

    pbar.close()


def upload_spans(client: Opik, span_data: List[span.SpanData]):
    pbar = tqdm.tqdm(desc="Uploading spans", unit=" spans", total=len(span_data))
    for span_data_ in span_data:
        client.span(**span_data_.__dict__)
        pbar.update(1)

    pbar.close()


if __name__ == "__main__":
    all_traces = []
    all_spans = []

    # download_traces(client)
    # download_spans(client)
    all_traces = convert_traces()
    all_spans = convert_spans()
    new_trace_data, new_span_data = prepare_data_for_copy(all_traces, all_spans)

    del all_traces
    del all_spans

    upload_traces(my_client, new_trace_data)
    # upload_spans(my_client, new_span_data)

    print()
