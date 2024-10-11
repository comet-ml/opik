---
sidebar_position: 3
sidebar_label: Log Distributed Traces
---

# Log Distributed Traces

When working with complex LLM applications, it is common to need to track a traces across multiple services. Opik supports distributed tracing out of the box when integrating using function decorators using a mechanism that is similar to how OpenTelemetry implements distributed tracing.

For the purposes of this guide, we will assume that you have a simple LLM application that is made up of two services: a client and a server. We will assume that the client will create the trace and span, while the server will add a nested span. In order to do this, the `trace_id` and `span_id` will be passed in the headers of the request from the client to the server.

![Distributed Tracing](/img/tracing/distributed_tracing.svg)

The Python SDK includes some helper functions to make it easier to fetch headers in the client and ingest them in the server:

```python title="client.py"
from opik import track, opik_context

@track()
def my_client_function(prompt: str) -> str:
    headers = {}
    
    # Update the headers to include Opik Trace ID and Span ID
    headers.update(opik_context.get_distributed_trace_headers())
    
    # Make call to backend service
    response = requests.post("http://.../generate_response", headers=headers, json={"prompt": prompt})
    return response.json()
```

On the server side, you can pass the headers to your decorated function:

```python title="server.py"
from opik import track
from fastapi import FastAPI, Request

@track()
def my_llm_application():
    pass

app = FastAPI()  # Or Flask, Django, or any other framework


@app.post("/generate_response")
def generate_llm_response(request: Request) -> str:
    return my_llm_application(opik_distributed_trace_headers=request.headers)
```

:::note
The `opik_distributed_trace_headers` parameter is added by the `track` decorato to each function that is decorated and is a dictionary with the keys `opik_trace_id` and `opik_parent_span_id`.
:::
