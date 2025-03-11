import datetime
import logging
from concurrent.futures import ThreadPoolExecutor, wait

import click
from opik import Opik

logging.basicConfig(level=logging.INFO, format="%(levelname)s [%(asctime)s]: %(message)s")

LOGGER = logging.getLogger(__name__)

MAX_WORKERS = 40

opik = Opik()


def search_spans(project_name: str, trace_id: str):
    try:
        opik.search_spans(project_name=project_name, trace_id=trace_id)
    except Exception:
        pass


@click.command()
@click.option('--project-name', help='Project name')
@click.option('--start-date', help='Start date')
@click.option('--end-date', help='End date')
def main(project_name: str, start_date: str, end_date: str):
    end_date = end_date + "T00:00:00Z" if end_date else (
            (datetime.date.fromisoformat(start_date) + datetime.timedelta(days=1)).isoformat() + "T00:00:00Z")
    start_date = start_date + "T00:00:00Z"
    filter_string = f'start_time >= "{start_date}" and end_time <= "{end_date}"'

    LOGGER.info("Searching traces for project name '%s', filter '%s'", project_name, filter_string)
    traces = opik.search_traces(project_name=project_name, filter_string=filter_string)
    LOGGER.info("Searched traces for project name '%s', filter '%s', total '%s'",
                project_name, filter_string, len(traces))

    futures = []
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        LOGGER.info("Searching spans for project name '%s', total traces '%s'", project_name, len(traces))
        for trace in traces:
            future = executor.submit(search_spans, project_name, trace.id)
            futures.append(future)

    LOGGER.info("Waiting to complete searching span, total calls '%s'", len(futures))
    wait(futures)
    LOGGER.info("Completed searching span, total calls '%s'", len(futures))


if __name__ == "__main__":
    main()
