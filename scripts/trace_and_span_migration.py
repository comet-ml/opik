"""
Downloading traces: 25000 traces [00:30, 818.92 traces/s]
Downloading spans: 25000 spans [00:59, 418.66 spans/s]

"""

import os
import pickle
import time

import tqdm

from opik import Opik
# from opik.api_objects import span, trace


PROJECT_NAME = ""
API_KEY = ""
WORKSPACE = ""
MAX_RESULTS_BATCH_SIZE = 1_000
MAX_RETRIES = 3

DATA_DIR = "./span_data/"

VERBOSE = 1
MAX_RESULTS = 25_000
SPAN_SAVE_BATCH_SIZE = 5_000

os.makedirs(DATA_DIR, exist_ok=True)

client = Opik(
    workspace=WORKSPACE,
    api_key=API_KEY,
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
            with open(f"{DATA_DIR}/traces_{page}.pkl", "wb") as f:
                pickle.dump(traces, f)
            traces = []

        page += 1

    # Save leftovers
    if len(traces) > 0:
        with open(f"{DATA_DIR}/traces_{page}.pkl", "wb") as f:
            pickle.dump(traces, f)

    pbar.close()
    print(f"Downloaded {trace_count} traces.")


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
                print(f"Got an error - page {page} - {e}")
                retries += 1
                time.sleep(5)

        if len(page_spans.content) == 0:
            break

        spans.extend(page_spans.content)
        span_count += len(page_spans.content)

        pbar.update(len(page_spans.content))

        if len(spans) >= SPAN_SAVE_BATCH_SIZE:
            with open(f"{DATA_DIR}/spans_{page}.pkl", "wb") as f:
                pickle.dump(spans, f)
            spans = []

        page += 1

    # Save leftovers
    if len(spans) > 0:
        with open(f"{DATA_DIR}/spans_{page}.pkl", "wb") as f:
            pickle.dump(spans, f)

    pbar.close()
    print(f"Downloaded {span_count} spans.")


def convert_traces():
    pass
    # trace_data = [
    #     trace.trace_public_to_trace_data(
    #         project_name=PROJECT_NAME, trace_public=trace_public_
    #     )
    #     for trace_public_ in traces_public
    # ]


def convert_spans():
    pass
    # span_data = [
    #     span.span_public_to_span_data(
    #         project_name=PROJECT_NAME, span_public_=span_public_
    #     )
    #     for span_public_ in spans_public
    # ]


def upload_traces(client: Opik):
    pass
    # for trace_data_ in new_trace_data:
    #     client.trace(**trace_data_.__dict__)


def upload_spans(client: Opik):
    pass
    # for span_data_ in new_span_data:
    #     client.span(**span_data_.__dict__)


download_traces(client)
download_spans(client)
convert_traces()
convert_spans()
upload_traces(client)
upload_spans(client)

print()
