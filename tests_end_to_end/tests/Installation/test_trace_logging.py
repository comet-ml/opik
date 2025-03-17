import pytest
import opik
import time
import logging

logger = logging.getLogger(__name__)

def test_trace_logging():
    """Test that we can log a trace to the local Opik instance."""
    logger.info("Configuring Opik to use local installation")
    opik.configure(use_local=True)
    
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
        output={"output": "test output"}
    )
    
    logger.info("Ending span and trace")
    span.end()
    trace.end()
    client.end()
    
    logger.info("Waiting for trace to be processed")
    time.sleep(2)

    logger.info("Verifying trace exists")
    client = opik.Opik()
    traces = client.get_traces(project_name="installation_test_project", size=10)
    
    trace_found = False
    for t in traces:
        if t["name"] == "installation-test-trace":
            trace_found = True
            break
    
    assert trace_found, "Trace was not found after logging"
    logger.info("Trace verification successful")
    client.end()
