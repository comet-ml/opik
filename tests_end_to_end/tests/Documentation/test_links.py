import csv
import pytest
import os
import allure


@pytest.fixture
def broken_links():
    """Parse the linkinator CSV output and return broken links."""
    broken_links = []
    print(os.getcwd())
    with open("tests/Documentation/output.csv", "r") as file:
        reader = csv.DictReader(file)
        for row in reader:
            if (
                row["state"] == "BROKEN"
                and (
                    "comet" in row["url"].lower()
                    or "ferndocs" in row["url"].lower()
                    or "opik" in row["url"].lower()
                )
                and ("~explorer" not in row["parent"].lower())
            ):
                broken_links.append(
                    {
                        "url": row["url"],
                        "status": row["status"],
                        "parent": row["parent"],
                    }
                )

    return broken_links


@allure.feature("Documentation")
@allure.story("Broken Links Check")
def test_no_broken_comet_links(broken_links):
    """Test that there are no broken links in Comet's documentation."""

    links_by_parent = {}
    for link in broken_links:
        parent = link["parent"]
        if parent not in links_by_parent:
            links_by_parent[parent] = []
        links_by_parent[parent].append(link)

    if broken_links:
        report = ["Found broken links in Comet documentation:"]
        for parent, links in links_by_parent.items():
            report.append(f"\nOn page: {parent}")
            for link in links:
                report.append(f"  - {link['url']} (Status: {link['status']})")

        report_str = "\n".join(report)
        print(report_str)

        allure.attach(report_str, "Broken Links Report", allure.attachment_type.TEXT)

        pytest.fail(report_str)

    allure.attach(
        "No broken Comet links found in documentation!",
        "Link Check Results",
        allure.attachment_type.TEXT,
    )
