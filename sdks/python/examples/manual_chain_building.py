import opik
import os

os.environ["OPIK_URL_OVERRIDE"] = "http://localhost:5173/api"
client = opik.Opik()

trace = client.trace(name="trace-1")
span1 = trace.span(name="span-1")
span2 = span1.span(name="span-2")
span2.end()
span1.end()

trace.end()

client.end()
