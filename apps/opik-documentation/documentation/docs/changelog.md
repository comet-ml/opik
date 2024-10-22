---
sidebar_position: 101
sidebar_label: Changelog
---

# Weekly Changelog

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

## Week of 2024-09-30

**Opik Dashboard**:

- Added option to delete experiments from the UI
- Updated empty state for projects with no traces
- Removed tooltip delay for the reason icon in the feedback score components

**SDK:**

- Introduced new `get_or_create_dataset` method to the `opik.Opik` client. This method will create a new dataset if it does not exist.
- When inserting items into a dataset, duplicate items are now silently ignored instead of being ingested.
