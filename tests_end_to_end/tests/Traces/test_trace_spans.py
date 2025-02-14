import pytest
from playwright.sync_api import expect
from page_objects.TracesPageSpansMenu import TracesPageSpansMenu
from page_objects.ProjectsPage import ProjectsPage
from page_objects.TracesPage import TracesPage


class TestTraceSpans:
    @pytest.mark.parametrize(
        "traces_fixture",
        ["log_traces_with_spans_low_level", "log_traces_with_spans_decorator"],
    )
    @pytest.mark.sanity
    def test_spans_of_traces(self, page, request, create_project, traces_fixture):
        """
        Checks that every trace has the correct number and names of spans defined in the sanity_config.yaml file
        1. Open the traces page of the project
        2. Go through each trace and click it
        3. Check that the spans are present in each trace
        """
        project_name = create_project
        _, span_config = request.getfixturevalue(traces_fixture)
        projects_page = ProjectsPage(page)
        projects_page.go_to_page()
        projects_page.click_project(project_name)
        traces_page = TracesPage(page)
        trace_names = traces_page.get_all_trace_names_on_page()

        for trace in trace_names:
            traces_page.click_first_trace_that_has_name(trace)
            spans_menu = TracesPageSpansMenu(page)
            for count in range(span_config["count"]):
                prefix = span_config["prefix"]
                spans_menu.check_span_exists_by_name(f"{prefix}{count}")
            spans_menu.page.keyboard.press("Escape")

    @pytest.mark.parametrize(
        "traces_fixture",
        ["log_traces_with_spans_low_level", "log_traces_with_spans_decorator"],
    )
    @pytest.mark.sanity
    def test_trace_and_span_details(
        self, page, request, create_project, traces_fixture
    ):
        """
        Checks that for each trace and spans, the attributes defined in sanity_config.yaml are present
        1. Go through each trace of the project
        2. Check the created tags are present
        3. Check the created feedback scores are present
        4. Check the defined metadata is present
        5. Go through each span of the traces and repeat 2-4
        """
        project_name = create_project
        trace_config, span_config = request.getfixturevalue(traces_fixture)
        projects_page = ProjectsPage(page)
        projects_page.go_to_page()
        projects_page.click_project(project_name)
        traces_page = TracesPage(page)
        traces_page.wait_for_traces_to_be_visible()
        trace_names = traces_page.get_all_trace_names_on_page()

        for trace in trace_names:
            traces_page.click_first_trace_that_has_name(trace)
            spans_menu = TracesPageSpansMenu(page)
            tag_names = trace_config["tags"]

            for tag in tag_names:
                spans_menu.check_tag_exists_by_name(tag)

            spans_menu.get_feedback_scores_tab().click()

            for score in trace_config["feedback_scores"]:
                expect(
                    page.get_by_role("cell", name=score["name"], exact=True).first
                ).to_be_visible()
                expect(
                    page.get_by_role(
                        "cell",
                        name=str(score["value"]),
                        exact=True,
                    ).first
                ).to_be_visible()

            spans_menu.get_metadata_tab().click()
            for md_key in trace_config["metadata"]:
                expect(
                    page.get_by_text(f"{md_key}: {trace_config['metadata'][md_key]}")
                ).to_be_visible()

            for count in range(span_config["count"]):
                prefix = span_config["prefix"]
                spans_menu.get_first_span_by_name(f"{prefix}{count}").click()

                spans_menu.get_feedback_scores_tab().click()
                for score in span_config["feedback_scores"]:
                    expect(
                        page.get_by_role("cell", name=score["name"], exact=True)
                    ).to_be_visible()
                    expect(
                        page.get_by_role(
                            "cell",
                            name=str(score["value"]),
                            exact=True,
                        )
                    ).to_be_visible()

                spans_menu.get_metadata_tab().click()
                for md_key in span_config["metadata"]:
                    expect(
                        page.get_by_text(f"{md_key}: {span_config['metadata'][md_key]}")
                    ).to_be_visible()

                # provisional patchy solution, sometimes when clicking through spans very fast some of them show up as "no data" and the test fails
                page.wait_for_timeout(500)
            spans_menu.page.keyboard.press("Escape")
