import threading
import time
from opik import track, flush_tracker
from opik.opik_context import get_distributed_trace_headers


@track()
def remote_function(x):
    time.sleep(0.1)
    return "output-from-remote-function"


def remote_node(x, opik_headers):
    remote_function(x, opik_distributed_trace_headers=opik_headers)


@track()
def local_function(x):
    opik_headers = get_distributed_trace_headers()

    t1 = threading.Thread(
        target=remote_node, args=("remote-function-input", opik_headers)
    )
    t1.start()
    t1.join()

    return "output-from-local-function"


local_function("local-function-input")
flush_tracker()
