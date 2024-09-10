import threading
from opik import track, flush_tracker
from opik import opik_context


@track()
def f3(x, thread_name):
    # creates trace1 with span2_1 in thread 1
    # creates trace2 with span2_2 in thread 2
    print(f"Done f3 from {thread_name}")
    opik_context.update_current_span(tags=[f"f3-thread-{thread_name}"])
    return f"f3 output from {thread_name}"


@track()
def f2(x):
    # creates span 1 attached to trace 0 and parent span0
    t1 = threading.Thread(target=f3, args=("f3-input-1", "thread-1"))
    t2 = threading.Thread(target=f3, args=("f3-input-2", "thread-2"))
    t1.start()
    t1.join()
    t2.start()
    t2.join()
    print("Done f2")
    return "f2 output"


@track()
def f1(x):
    # creates trace 0 with span 0
    f2("f2 input")
    print("Done f1")
    return "f1 output"


f1("f1 input")
flush_tracker()
