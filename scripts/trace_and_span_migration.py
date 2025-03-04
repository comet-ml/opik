"""
Downloading traces: 25000 traces [00:30, 818.92 traces/s]
Downloading spans: 25000 spans [00:59, 418.66 spans/s]

"""
import datetime
import logging
import os
import pathlib
import pickle
import time
from collections import deque
from typing import List

import tqdm

from opik import Opik, id_helpers
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
MAX_RETRIES = 1_000

DATA_DIR = "./span_data/"
CONVERTED_DATA_DIR = "./span_data_converted/"

VERBOSE = 1
MAX_RESULTS = 25_000_000
SPAN_SAVE_BATCH_SIZE = 5_000

# rate limit settings
RATE_LIMIT = 8_000
TIME_WINDOW = 60.0

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
                LOGGER.info(f"Got an error - page {page} - retry#{retries} - {e}")
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
                LOGGER.info(f"Got an error - page {page} - retry#{retries} - {e}")
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


def convert_traces():
    LOGGER.info("Converting traces...")
    trace_files = [f for f in os.listdir(DATA_DIR) if f.startswith("traces_") and f.endswith(".pkl")]
    trace_files = sorted(trace_files)

    trace_id_mapping = {}
    trace_count = 0

    for trace_file in trace_files:
        file_path = os.path.join(DATA_DIR, trace_file)
        with open(file_path, "rb") as f:
            traces_public = pickle.load(f)

        all_trace_data = []
        for trace_public_ in traces_public:
            # covert from `public` to `data` representation
            trace_ = trace.trace_public_to_trace_data(project_name=PROJECT_NAME, trace_public=trace_public_)

            # replace ID with a new one
            id = id_helpers.generate_id(trace_.start_time)
            trace_id_mapping[trace_.id] = id
            trace_.id = id
            trace_.project_name = DESTINATION_PROJECT_NAME

            all_trace_data.append(trace_)
            trace_count += 1

        converted_file_path = pathlib.Path(CONVERTED_DATA_DIR) / pathlib.Path(file_path).name
        with open(converted_file_path, "wb") as f:
            pickle.dump(all_trace_data, f)

    LOGGER.info(f"Converted {trace_count} traces.")

    # save mappings
    converted_file_path = pathlib.Path(CONVERTED_DATA_DIR) / "trace_mapping.pkl"
    with open(converted_file_path, "wb") as f:
        pickle.dump(trace_id_mapping, f)


def convert_spans():
    LOGGER.info("Converting spans... stage #1")
    span_files = [f for f in os.listdir(DATA_DIR) if f.startswith("spans_") and f.endswith(".pkl")]
    span_files = sorted(span_files)

    span_count = 0

    span_id_mapping = {}

    # FIRST RUN - MAP NEW ID FOR ALL SPANS
    for span_file in span_files:
        file_path = os.path.join(DATA_DIR, span_file)
        with open(file_path, "rb") as f:
            spans_public = pickle.load(f)

        all_span_data = []
        for span_public_ in spans_public:
            # covert from `public` to `data` representation
            span_ = span.span_public_to_span_data(project_name=PROJECT_NAME, span_public_=span_public_)

            id = id_helpers.generate_id(span_.start_time)
            span_id_mapping[span_.id] = id

            all_span_data.append(span_)
            span_count += 1

        converted_file_path = pathlib.Path(CONVERTED_DATA_DIR) / pathlib.Path(file_path).name
        with open(converted_file_path, "wb") as f:
            pickle.dump(all_span_data, f)

    LOGGER.info(f"Stage 1: Converted {span_count} spans.")

    # save mappings
    converted_file_path = pathlib.Path(CONVERTED_DATA_DIR) / "span_mapping.pkl"
    with open(converted_file_path, "wb") as f:
        pickle.dump(span_id_mapping, f)

    # SECOND RUN - REPLACE ID
    LOGGER.info("Converting spans... stage #2")
    span_files = [f for f in os.listdir(CONVERTED_DATA_DIR) if f.startswith("spans_") and f.endswith(".pkl")]
    span_files = sorted(span_files)

    span_count = 0

    orphan_trace_ids = set()
    orphan_span_trace_ids = set()

    orphan_parent_span_ids = set()
    orphan_span_parent_span_ids = set()

    trace_mappings_file_path = pathlib.Path(CONVERTED_DATA_DIR) / "trace_mapping.pkl"
    with open(trace_mappings_file_path, "rb") as f:
        trace_id_mapping = pickle.load(f)

    for span_file in span_files:
        file_path = os.path.join(CONVERTED_DATA_DIR, span_file)
        with open(file_path, "rb") as f:
            spans_data = pickle.load(f)

        all_span_data = []
        for spans_data_ in spans_data:
            if spans_data_.trace_id not in trace_id_mapping:
                # LOGGER.warning(
                #     "While copying a span to a new project, found orphan span that will not be copied with id: %s and trace id: %s",
                #     spans_data_.id,
                #     spans_data_.trace_id,
                # )
                orphan_trace_ids.add(spans_data_.trace_id)
                orphan_span_trace_ids.add(spans_data_.id)
                continue

            spans_data_.project_name = DESTINATION_PROJECT_NAME
            spans_data_.trace_id = trace_id_mapping[spans_data_.trace_id]

            if spans_data_.parent_span_id is not None:
                if spans_data_.parent_span_id not in span_id_mapping:
                    # LOGGER.warning(
                    #     "While copying a span to a new project, found orphan span with parent span id that will not be copied with id: %s and parent_span id: %s",
                    #     spans_data_.id,
                    #     spans_data_.parent_span_id,
                    # )
                    orphan_parent_span_ids.add(spans_data_.parent_span_id)
                    orphan_span_parent_span_ids.add(spans_data_.id)
                    continue

                spans_data_.parent_span_id = span_id_mapping.get(spans_data_.parent_span_id)

            spans_data_.id = span_id_mapping[spans_data_.id]

            all_span_data.append(spans_data_)
            span_count += 1

        # rewrite converted file with updated data
        with open(file_path, "wb") as f:
            pickle.dump(all_span_data, f)

    LOGGER.info(f"Stage 2: Converted {span_count} spans.")
    LOGGER.info(f"Orhan trace ids: {len(orphan_trace_ids)}")
    LOGGER.info(f"Orhan parent_span ids: {len(orphan_parent_span_ids)}")
    LOGGER.info(f"Orhan span with trace ids: {len(orphan_span_trace_ids)}")
    LOGGER.info(f"Orhan span with parent_span ids: {len(orphan_span_parent_span_ids)}")


# def upload_traces(client: Opik, trace_data: List[trace.TraceData]):
def upload_traces(client: Opik):
    trace_files = [f for f in os.listdir(CONVERTED_DATA_DIR) if f.startswith("traces_") and f.endswith(".pkl")]
    trace_files = sorted(trace_files)

    last_call_times = deque(maxlen=RATE_LIMIT)

    # pbar = tqdm.tqdm(desc="Uploading traces", unit=" traces", total=len(trace_data))
    pbar = tqdm.tqdm(desc="Uploading traces", unit=" traces")

    for trace_file in trace_files:
        file_path = os.path.join(DATA_DIR, trace_file)
        with open(file_path, "rb") as f:
            trace_data = pickle.load(f)

        for trace_data_ in trace_data:
            current_time = time.time()

            if len(last_call_times) >= RATE_LIMIT:
                earliest_call_time = last_call_times[0]
                while current_time - earliest_call_time < TIME_WINDOW:
                    time.sleep(0.01)
                    current_time = time.time()

            client.trace(**trace_data_.__dict__)
            last_call_times.append(current_time)

            pbar.update(1)
        client.flush()

    pbar.close()


def upload_spans(client: Opik, span_data: List[span.SpanData]):
    last_call_times = deque(maxlen=RATE_LIMIT)

    pbar = tqdm.tqdm(desc="Uploading spans", unit=" spans", total=len(span_data))

    for span_data_ in span_data:

        current_time = time.time()

        if len(last_call_times) >= RATE_LIMIT:
            earliest_call_time = last_call_times[0]
            while current_time - earliest_call_time < TIME_WINDOW:
                time.sleep(0.01)
                current_time = time.time()

        client.span(**span_data_.__dict__)
        last_call_times.append(current_time)

        pbar.update(1)

    pbar.close()


if __name__ == "__main__":
    start_time = datetime.datetime.now()

    # download_traces(client)
    # download_spans(client)
    convert_traces()
    convert_spans()

    # upload_traces(my_client, new_trace_data)
    # upload_spans(my_client, new_span_data)

    LOGGER.info(datetime.datetime.now() - start_time)
