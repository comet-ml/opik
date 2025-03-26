import opik
import time
import logging
from opik.rest_api.client import OpikApi

logger = logging.getLogger(__name__)


def test_trace_logging():
    """Test that we can log a trace to the local Opik instance."""
    logger.info("Configuring Opik to use local installation")
    opik.configure(use_local=True, url="http://localhost:5173", force=True)

    logger.info("Creating Opik client")
    client = opik.Opik()
    logger.info("Creating trace")
    trace = client.trace(
        name="installation-test-trace",
        project_name="installation_test_project",
        input={"input": "test input"},
        output={"output": "test output"},
    )

    logger.info("Creating span")
    span = trace.span(
        name="test-span",
        input={"input": "test input"},
        output={"output": "test output"},
    )

    logger.info("Ending span and trace")
    span.end()
    trace.end()
    client.end()

    logger.info("Waiting for trace to be processed")
    time.sleep(2)

    logger.info("Verifying trace exists")

    api_client = OpikApi(base_url="http://localhost:5173/api")

    response = api_client.traces.get_traces_by_project(
        project_name="installation_test_project", page=1, size=1
    )
    traces = response.dict()["content"]

    print(traces)
