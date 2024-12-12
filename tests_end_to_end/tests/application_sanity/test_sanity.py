from playwright.sync_api import Page, expect

from page_objects.TracesPageSpansMenu import TracesPageSpansMenu
from page_objects.IndividualDatasetPage import IndividualDatasetPage


def test_project_name(
    projects_page_timeout,
    log_traces_and_spans_decorator,
    log_traces_and_spans_low_level,
):
    """
    Checks that the project created via the fixtures exists
    Does a timeout of 5 seconds to wait for the traces to show up in UI for later tests (TODO: figure out a better way to do this)

    1. Open projects page
    2. Check the created project exists
    """
    projects_page_timeout.check_project_exists_on_current_page("test-project")


def test_traces_created(
    traces_page, config, log_traces_and_spans_low_level, log_traces_and_spans_decorator
):
    """
    Checks that every trace defined in the sanity_config file is present in the project

    1. Open the traces page of the project
    2. Grab all the names of the traces (should never set more than 15 in config so 1 page is safe)
    3. Check that every possible name of the traces as defined in sanity_config.yaml is present in the names list
    """
    trace_names = traces_page.get_all_trace_names_on_page()

    client_prefix = config["traces"]["client"]["prefix"]
    decorator_prefix = config["traces"]["decorator"]["prefix"]

    for count in range(config["traces"]["count"]):
        for prefix in [client_prefix, decorator_prefix]:
            assert prefix + str(count) in trace_names


def test_spans_of_traces(
    page,
    traces_page,
    config,
    log_traces_and_spans_low_level,
    log_traces_and_spans_decorator,
):
    """
    Checks that every trace has the correct number and names of spans defined in the sanity_config.yaml file
    1. Open the traces page of the project
    2. Go through each trace and click it
    3. Check that the spans are present in each trace
    """
    trace_names = traces_page.get_all_trace_names_on_page()
    traces_page.click_first_trace_that_has_name("decorator-trace-1")

    for trace in trace_names:
        traces_page.click_first_trace_that_has_name(trace)
        spans_menu = TracesPageSpansMenu(page)
        trace_type = trace.split("-")[0]  # 'client' or 'decorator'
        for count in range(config["spans"]["count"]):
            prefix = config["spans"][trace_type]["prefix"]
            spans_menu.check_span_exists_by_name(f"{prefix}{count}")


def test_trace_and_span_details(
    page,
    traces_page,
    config,
    log_traces_and_spans_low_level,
    log_traces_and_spans_decorator,
):
    """
    Checks that for each trace and spans, the attributes defined in sanity_config.yaml are present
    1. Go through each trace of the project
    2. Check the created tags are present
    3. Check the created feedback scores are present
    4. Check the defined metadata is present
    5. Go through each span of the traces and repeat 2-4
    """
    trace_names = traces_page.get_all_trace_names_on_page()

    for trace in trace_names:
        traces_page.click_first_trace_that_has_name(trace)
        spans_menu = TracesPageSpansMenu(page)
        trace_type = trace.split("-")[0]
        tag_names = config["traces"][trace_type]["tags"]

        for tag in tag_names:
            spans_menu.check_tag_exists_by_name(tag)

        spans_menu.get_feedback_scores_tab().click()

        for score in config["traces"][trace_type]["feedback-scores"]:
            expect(
                page.get_by_role("cell", name=score, exact=True).first
            ).to_be_visible()
            expect(
                page.get_by_role(
                    "cell",
                    name=str(config["traces"][trace_type]["feedback-scores"][score]),
                    exact=True,
                ).first
            ).to_be_visible()

        spans_menu.get_metadata_tab().click()
        for md_key in config["traces"][trace_type]["metadata"]:
            expect(
                page.get_by_text(
                    f"{md_key}: {config['traces'][trace_type]['metadata'][md_key]}"
                )
            ).to_be_visible()

        for count in range(config["spans"]["count"]):
            prefix = config["spans"][trace_type]["prefix"]
            spans_menu.get_first_span_by_name(f"{prefix}{count}").click()

            spans_menu.get_feedback_scores_tab().click()
            for score in config["spans"][trace_type]["feedback-scores"]:
                expect(page.get_by_role("cell", name=score, exact=True)).to_be_visible()
                expect(
                    page.get_by_role(
                        "cell",
                        name=str(config["spans"][trace_type]["feedback-scores"][score]),
                        exact=True,
                    )
                ).to_be_visible()

            spans_menu.get_metadata_tab().click()
            for md_key in config["spans"][trace_type]["metadata"]:
                expect(
                    page.get_by_text(
                        f"{md_key}: {config['spans'][trace_type]['metadata'][md_key]}"
                    )
                ).to_be_visible()

            # provisional patchy solution, sometimes when clicking through spans very fast some of them show up as "no data" and the test fails
            page.wait_for_timeout(500)


def test_dataset_name(datasets_page, config, dataset):
    """
    Checks that the dataset created via the fixture as defined in sanity_config.yaml is present on the datasets page
    """
    datasets_page.check_dataset_exists_on_page_by_name(config["dataset"]["name"])


def test_dataset_items(page: Page, datasets_page, config, dataset_content):
    """
    Checks that the traces created via the fixture and defined in sanity_dataset.jsonl are present within the dataset
    """
    datasets_page.select_database_by_name(config["dataset"]["name"])

    individual_dataset_page = IndividualDatasetPage(page)
    for item in dataset_content:
        individual_dataset_page.check_cell_exists_by_text(item["input"])
        individual_dataset_page.check_cell_exists_by_text(item["expected_output"])


def test_experiments_exist(experiments_page, config, create_experiments):
    """
    Checks that the experiments created via the fixture are present and have the correct values for the metrics (experiments defined in a way to always return the same results)
    """
    experiments_page.check_experiment_exists_by_name("test-experiment-Equals")
    experiments_page.page.get_by_role("link", name="test-experiment-Equals").click()
    assert (
        "Equals\n0"
        == experiments_page.page.get_by_test_id("feedback-score-tag").first.inner_text()
    )

    experiments_page.page.get_by_role("link", name="Experiments").click()

    experiments_page.check_experiment_exists_by_name("test-experiment-Contains")
    experiments_page.page.get_by_role("link", name="test-experiment-Contains").click()
    assert (
        "Contains\n1"
        == experiments_page.page.get_by_test_id("feedback-score-tag").first.inner_text()
    )
