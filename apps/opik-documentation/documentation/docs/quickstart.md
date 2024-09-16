---
sidebar_position: 2
sidebar_label: Quickstart
---

# Quickstart

This guide helps you integrate the Opik platform with your existing LLM application.

## Set up

Getting started is as simple as creating an [account on Comet](https://www.comet.com/signup?from=llm) or [self-hosting the platform](/self-host/overview.md).

Once your account is created, you can start logging traces by installing the Opik Python SDK:

```bash
pip install opik
```

and configuring the SDK with:

```python
import os

os.environ["OPIK_API_KEY"] = "<your-api-key>"
os.environ["OPIK_WORKSPACE"] = "<your-workspace>"
```

You can find your Opik API key in the user menu of the [Opik UI](https://www.comet.com/opik/), the workspace name is the first item of the breadcrumbs and often the same as your username.


:::tip
If you are self-hosting the platform, you don't need to set the `OPIK_API_KEY` and `OPIK_WORKSPACE` environment variables. Instead simply set:

    export OPIK_URL_OVERRIDE="http://localhost:5173/api"
:::

## Integrating with your LLM application

You can start logging traces to Opik by simply adding the `@track` decorator to your LLM application:

```python
from opik import track

@track
def your_llm_application(input):
    # Your existing LLM application logic here
    output = "..."
    return output
```

To learn more about the `track` decorator, see the [track documentation](/tracing/log_traces.md#logging-traces-and-spans). Once the traces are logged, you can view them in the OPIK UI:

![Opik Traces](/img/home/traces_page_for_quickstart.png)
