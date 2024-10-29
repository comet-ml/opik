---
sidebar_position: 101
sidebar_label: Changelog
---

# Weekly Changelog

## Week of 2024-10-28

**SDK**:
- Released new evaluation metric: [GEval](/evaluation/metrics/g_eval.md) - This LLM as a Judge metric is task agnostic and can be used to evaluate any LLM call based on your own custom evaluation criteria.
- Allow users to specify the path to the Opik configuration file using the `OPIK_CONFIG_PATH` environment variable, read more about it in the [Python SDK Configuration guide](/tracing/sdk_configuration.mdx#using-a-configuration-file).

## Week of 2024-10-21

**Opik Dashboard**:

- Added the option to download traces and LLM calls as CSV files from the UI:
    ![download traces](/img/changelog/2024-10-21/download_traces.png)
- Introduce a new quickstart guide to help you get started:
    ![quickstart guide](/img/changelog/2024-10-21/quickstart_guide.png)
- Updated datasets to support more flexible data schema, you can now insert items with any key value pairs and not just `input` and `expected_output`. See more in the SDK section below.
- Multiple small UX improvements (more informative empty state for projects, updated icons, feedback tab in the experiment page, etc).
- Fix issue with `\t` characters breaking the YAML code block in the traces page.

**SDK**:

- Datasets now support more flexible data schema, we now support inserting items with any key value pairs:

    ```python
    import opik

    client = opik.Opik()
    dataset = client.get_or_create_dataset(name="Demo Dataset")
    dataset.insert([
        {"user_question": "Hello, what can you do ?", "expected_output": {"assistant_answer": "I am a chatbot assistant that can answer questions and help you with your queries!"}},
        {"user_question": "What is the capital of France?", "expected_output": {"assistant_answer": "Paris"}},
    ])
    ```
- Released WatsonX, Gemini and Groq integration based on the LiteLLM integration.
- The `context` field is now optional in the [Hallucination](/tracing/integrations/overview.md) metric.
- LLM as a Judge metrics now support customizing the LLM provider by specifying the `model` parameter. See more in the [Customizing LLM as a Judge metrics](/evaluation/metrics/overview.md#customizing-llm-as-a-judge-metrics) section.
- Fixed an issue when updating feedback scores using the `update_current_span` and `update_current_trace` methods. See this Github issue for more details.


## Week of 2024-10-14

**Opik Dashboard**:

- Fix handling of large experiment names in breadcrumbs and popups
- Add filtering options for experiment items in the experiment page
    ![experiment item filters](/img/changelog/2024-10-14/experiment_page_filtering.png)

**SDK:**

- Allow users to configure the project name in the LangChain integration

## Week of 2024-10-07

**Opik Dashboard**:

- Added `Updated At` column in the project page
- Added support for filtering by token usage in the trace page

**SDK:**

- Added link to the trace project when traces are logged for the first time in a session
- Added link to the experiment page when calling the `evaluate` method
- Added `project_name` parameter in the `opik.Opik` client and `opik.track` decorator
- Added a new `nb_samples` parameter in the `evaluate` method to specify the number of samples to use for the evaluation
- Released the LiteLLM integration

## Week of 2024-09-30

**Opik Dashboard**:

- Added option to delete experiments from the UI
- Updated empty state for projects with no traces
- Removed tooltip delay for the reason icon in the feedback score components

**SDK:**

- Introduced new `get_or_create_dataset` method to the `opik.Opik` client. This method will create a new dataset if it does not exist.
- When inserting items into a dataset, duplicate items are now silently ignored instead of being ingested.
