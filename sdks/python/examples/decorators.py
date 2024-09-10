from opik import track, flush_tracker
from opik import opik_context


@track()
def f3(x):
    # creates span3 attached to trace1 with parent span2
    opik_context.update_current_span(tags=["tag-f3"])
    print("Done f3")
    return "f3 output"


@track()
def f2(x):
    # creates span2 attached to trace1 with parent span1
    f3("f3 input")
    print("Done f2")
    return "f2 output"


@track()
def f1(x, y, z=1):
    # creates trace 1 and span 1
    f2("f2 input")
    print("Done f1")
    return "f1 output"


f1("f1 input", 42)
flush_tracker()
