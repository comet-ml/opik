---
sidebar_label: Exporting Traces
---

# Exporting Traces

You can export the traces you have logged to the Opik platform using:

1. Using the Opik SDK: You can use the [`Opik.search_traces`](https://www.comet.com/docs/opik/python-sdk-reference/Opik.html#opik.Opik.search_traces) method to export traces.
2. Using the Opik REST API: You can use the [`/traces`](/reference/rest_api/get-traces-by-project.api.mdx) endpoint to export traces.
3. Using the UI: Once you have selected the traces you want to export, you can click on the `Export CSV` button in the `Actions` dropdown.

:::tip
The recommended way to export traces is to use the [`Opik.search_traces`](https://www.comet.com/docs/opik/python-sdk-reference/Opik.html#opik.Opik.search_traces) method in the Opik SDK.
:::

## Using the Opik SDK

The [`Opik.search_traces`](https://www.comet.com/docs/opik/python-sdk-reference/Opik.html#opik.Opik.search_traces) method allows you to both export all the traces in a project or search for specific traces and export them.

### Exporting all traces

To export all traces, you will need to specify a `max_results` value that is higher than the total number of traces in your project:

```python
import opik

client = opik.Opik()

traces = client.search_traces(project_name="Default project", max_results=1000000)
```

### Search for specific traces

You can use the `filter_string` parameter to search for specific traces:

```python
import opik

client = opik.Opik()

traces = client.search_traces(project_name="Default project", filter_string='input contains "Opik"')

# Convert to Dict if required
traces = [trace.dict() for trace in traces]
```

The `filter_string` parameter should follow the format `<column> <operator> <value>` with:

1. `<column>`: The column to filter on, these can be:
   - `name`
   - `input`
   - `output`
   - `start_time`
   - `end_time`
   - `metadata`
   - `feedback_score`
   - `tags`
   - `usage.total_tokens`
   - `usage.prompt_tokens`
   - `usage.completion_tokens`.
2. `<operator>`: The operator to use for the filter, this can be `=`, `!=`, `>`, `>=`, `<`, `<=`, `contains`, `not_contains`. Not that not all operators are supported for all columns.
3. `<value>`: The value to filter on. If you are filtering on a string, you will need to wrap it in double quotes.

Here are some additional examples of valid `filter_string` values:

```python
import opik

client = opik.Opik(
    project_name="Default project"
)

traces = client.search_traces(filter_string='input contains "Opik"')
traces = client.search_traces(filter_string='start_time >= "2024-01-01T00:00:00Z"')
traces = client.search_traces(filter_string='tags contains "production"')
traces = client.search_traces(filter_string='usage.total_tokens > 1000')
traces = client.search_traces(filter_string='metadata.model = "gpt-4o"')
```

## Using the Opik REST API

To export traces using the Opik REST API, you can use the [`/traces`](/reference/rest_api/get-traces-by-project.api.mdx) endpoint. This endpoint is paginated so you will need to make multiple requests to retrieve all the traces you want.

To search for specific traces, you can use the `filter` parameter. While this is a string parameter, it does not follow the same format as the `filter_string` parameter in the Opik SDK. Instead it is a list of json objects with the following format:

```json
[
  {
    "field": "name",
    "type": "string",
    "operator": "=",
    "value": "Opik"
  }
]
```

:::warning
The `filter` parameter was designed to be used with the Opik UI and is therefore not very flexible. If you need more flexibility,
please raise an issue on [GitHub](https://github.com/comet-ml/opik/issues) so we can help.
:::

## Using the UI

To export traces as a CSV file from the UI, you can simply select the traces you wish to export and click on `Export CSV` in the `Actions` dropdown:

![Export CSV](/img/tracing/download_traces.png)

:::tip
The UI only allows you to export up to 100 traces at a time as it is linked to the page size of the traces table. If you need to export more traces, we recommend using the Opik SDK.
:::
