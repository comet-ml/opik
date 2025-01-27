---
sidebar_label: Changelog
description: Weelkly changelog for Opik
pytest_codeblocks_skip: true
---

# Weekly Changelog

## Week of 2025-01-20

**Opik Dashboard**:

- Added logs for online evaluation rules so that you can more easily ensure your online evaluation metrics are working as expected
- Added auto-complete support in the variable mapping section of the online evaluation rules modal
- Added support for Anthropic models in the playground
- Experiments are now created when using datasets in the playground
- Improved the Opik home page
- Updated the code snippets in the quickstart to make them easier to understand

**SDK**:

- Improved support for litellm completion kwargs
- LiteLLM required version is now relaxed to avoid conflicts with other Python packages

## Week of 2025-01-13

**Opik Dashboard**:

- Datasets are now supported in the playground allowing you to quickly evaluate prompts on multiple samples
- Updated the models supported in the playground
- Updated the quickstart guides to include all the supported integrations
- Fix issue that means traces with text inputs can't be added to datasets
- Add the ability to edit dataset descriptions in the UI
- Released [online evaluation](/production/rules.md) rules - You can now define LLM as a Judge metrics that will automatically score all, or a subset, of your production traces.

![Online evaluation](/img/changelog/2025-01-13/online_evaluation.gif)

**SDK**:

- New integration with [CrewAI](/tracing/integrations/crewai.md)
- Released a new `evaluate_prompt` method that simplifies the evaluation of simple prompts templates
- Added Sentry to the Python SDK so we can more easily

## Week of 2025-01-06

**Opik Dashboard**:

- Fixed an issue with the trace viewer in Safari

**SDK**:

- Added a new `py.typed` file to the SDK to make it compatible with mypy

## Week of 2024-12-30

**Opik Dashboard**:

- Added duration chart to the project dashboard
- Prompt metadata can now be set and viewed in the UI, this can be used to store any additional information about the prompt
- Playground prompts and settings are now cached when you navigate away from the page

**SDK**:

- Introduced a new `OPIK_TRACK_DISABLE` environment variable to disable the tracking of traces and spans
- We now log usage information for traces logged using the LlamaIndex integration

## Week of 2024-12-23

**SDK**:

- Improved error messages when getting a rate limit when using the `evaluate` method
- Added support for a new metadata field in the `Prompt` object, this field is used to store any additional information about the prompt.
- Updated the library used to create uuidv7 IDs
- New Guardrails integration
- New DSPY integration

## Week of 2024-12-16

**Opik Dashboard**:

- The Opik playground is now in public preview
  ![playground](/img/changelog/2024-12-16/playground.png)
- You can now view the prompt diff when updating a prompt from the UI
- Errors in traces and spans are now displayed in the UI
- Display agent graphs in the traces sidebar
- Released a new plugin for the [Kong AI Gateway](/production/gateway.mdx)

**SDK**:

- Added support for serializing Pydantic models passed to decorated functions
- Implemented `get_experiment_by_id` and `get_experiment_by_name` methods
- Scoring metrics are now logged to the traces when using the `evaluate` method
- New integration with [aisuite](/tracing/integrations/aisuite.md)
- New integration with [Haystack](/tracing/integrations/haystack.md)

## Week of 2024-12-09

**Opik Dashboard**:

- Updated the experiments pages to make it easier to analyze the results of each experiment. Columns are now organized based on where they came from (dataset, evaluation task, etc) and output keys are now displayed in multiple columns to make it easier to review
  ![experiment item table](/img/changelog/2024-12-09/experiment_items_table.png)
- Improved the performance of the experiments so experiment items load faster
- Added descriptions for projects

**SDK**:

- Add cost tracking for OpenAI calls made using LangChain
- Fixed a timeout issue when calling `get_or_create_dataset`

## Week of 2024-12-02

**Opik Dashboard**:

- Added a new `created_by` column for each table to indicate who created the record
- Mask the API key in the user menu

**SDK**:

- Implement background batch sending of traces to speed up processing of trace creation requests
- Updated OpenAI integration to track cost of LLM calls
- Updated `prompt.format` method to raise an error when it is called with the wrong arguments
- Updated the `Opik` method so it accepts the `api_key` parameter as a positional argument
- Improved the prompt template for the `hallucination` metric
- Introduced a new `opik_check_tls_certificate` configuration option to disable the TLS certificate check.

## Week of 2024-11-25

**Opik Dashboard**:

- Feedback scores are now displayed as separate columns in the traces and spans table
- Introduce a new project dashboard to see trace count, feedback scores and token count over time.
  ![project dashboard](/img/changelog/2024-11-25/project_dashboard.png)
- Project statistics are now displayed in the traces and spans table header, this is especially useful for tracking the average feedback scores
  ![project statistics](/img/changelog/2024-11-25/project_statistics.png)
- Redesigned the experiment item sidebar to make it easier to review experiment results
  ![experiment item sidebar](/img/changelog/2024-11-25/experiment_item_sidebar.png)
- Annotating feedback scores in the UI now feels much faster
- Support exporting traces as JSON file in addition to CSV
- Sidebars now close when clicking outside of them
- Dataset groups in the experiment page are now sorted by last updated date
- Updated scrollbar styles for Windows users

**SDK**:

- Improved the robustness to connection issues by adding retry logic.
- Updated the OpenAI integration to track structured output calls using `beta.chat.completions.parse`.
- Fixed issue with `update_current_span` and `update_current_trace` that did not support updating the `output` field.

## Week of 2024-11-18

**Opik Dashboard**:

- Updated the majority of tables to increase the information density, it is now easier to review many traces at once.
- Images logged to datasets and experiments are now displayed in the UI. Both images urls and base64 encoded images are supported.

**SDK**:

- The `scoring_metrics` argument is now optional in the `evaluate` method. This is useful if you are looking at evaluating your LLM calls manually in the Opik UI.
- When uploading a dataset, the SDK now prints a link to the dataset in the UI.
- Usage is now correctly logged when using the LangChain OpenAI integration.
- Implement a batching mechanism for uploading spans and dataset items to avoid `413 Request Entity Too Large` errors.
- Removed pandas and numpy as mandatory dependencies.

## Week of 2024-11-11

**Opik Dashboard**:

- Added the option to sort the projects table by `Last updated`, `Created at` and `Name` columns.
- Updated the logic for displaying images, instead of relying on the format of the response, we now use regex rules to detect if the trace or span input includes a base64 encoded image or url.
- Improved performance of the Traces table by truncating trace inputs and outputs if they contain base64 encoded images.
- Fixed some issues with rendering trace input and outputs in YAML format.
- Added grouping and charts to the experiments page:
  ![experiment summary](/img/changelog/2024-11-11/experiment_summary.png)

**SDK**:

- **New integration**: Anthropic integration

  ```python
  from anthropic import Anthropic, AsyncAnthropic
  from opik.integrations.anthropic import track_anthropic

  client = Anthropic()
  client = track_anthropic(client, project_name="anthropic-example")

  message = client.messages.create(
        max_tokens=1024,
        messages=[
            {
                "role": "user",
                "content": "Tell a fact",
            }
        ],
        model="claude-3-opus-20240229",
    )
  print(message)
  ```

- Added a new `evaluate_experiment` method in the SDK that can be used to re-score an existing experiment, learn more in the [Update experiments](/evaluation/update_existing_experiment.md) guide.

## Week of 2024-11-04

**Opik Dashboard**:

- Added a new `Prompt library` page to manage your prompts in the UI.
  ![prompt library](/img/changelog/2024-11-04/prompt_library_versions.png)

**SDK**:

- Introduced the `Prompt` object in the SDK to manage prompts stored in the library. See the [Prompt Management](/prompt_engineering/managing_prompts_in_code.mdx) guide for more details.
- Introduced a `Opik.search_spans` method to search for spans in a project. See the [Search spans](/tracing/export_data.md#exporting-spans) guide for more details.
- Released a new integration with [AWS Bedrock](/tracing/integrations/bedrock.md) for using Opik with Bedrock models.

## Week of 2024-10-28

**Opik Dashboard**:

- Added a new `Feedback modal` in the UI so you can easily provide feedback on any parts of the platform.

**SDK**:

- Released new evaluation metric: [GEval](/evaluation/metrics/g_eval.md) - This LLM as a Judge metric is task agnostic and can be used to evaluate any LLM call based on your own custom evaluation criteria.
- Allow users to specify the path to the Opik configuration file using the `OPIK_CONFIG_PATH` environment variable, read more about it in the [Python SDK Configuration guide](/tracing/sdk_configuration.mdx#using-a-configuration-file).
- You can now configure the `project_name` as part of the `evaluate` method so that traces are logged to a specific project instead of the default one.
- Added a new `Opik.search_traces` method to search for traces, this includes support for a search string to return only specific traces.
- Enforce structured outputs for LLM as a Judge metrics so that they are more reliable (they will no longer fail when decoding the LLM response).

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
