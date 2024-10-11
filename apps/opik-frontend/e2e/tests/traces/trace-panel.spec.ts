import { test } from "@e2e/fixtures";
import { expect } from "@playwright/test";
import { FeedbackScoreData } from "@e2e/entities";
import { SPAN_1, SPAN_2, TRACE_1, TRACE_SCORE } from "@e2e/test-data";

const TRACE_TAG_NAME = "trace_tag_test";
const SPAN_TAG_NAME = "span_tag_test";

test.describe("Trace panel", () => {
  test("Check collapse/expand in spans tree", async ({
    page,
    project,
    trace1,
    // we need to keep span param to generate data
    span,
    tracesPage,
  }) => {
    await tracesPage.goto(project.id);
    await tracesPage.openSidePanel(trace1.name);

    // check if tree is expanded
    await expect(
      tracesPage.sidePanel.container.getByRole("button", {
        name: "Collapse all",
      }),
    ).toBeVisible();
    await expect(page.locator("ul.level-0")).toBeVisible();
    await expect(page.locator("ul.level-1")).toBeVisible();
    await expect(page.locator("ul.level-2")).toBeVisible();

    // collapse tree
    await tracesPage.sidePanel.container
      .getByRole("button", {
        name: "Collapse all",
      })
      .click();

    // check if tree is collapsed
    await expect(page.locator("ul.level-0")).toBeVisible();
    await expect(page.locator("ul.level-1")).not.toBeVisible();
    await expect(page.locator("ul.level-2")).not.toBeVisible();

    await expect(
      tracesPage.sidePanel.container.getByRole("button", {
        name: "Expand all",
      }),
    ).toBeVisible();
  });

  test("Check rendered data in spans tree", async ({
    page,
    project,
    trace1,
    span,
    tracesPage,
  }) => {
    await tracesPage.goto(project.id);
    await tracesPage.openSidePanel(trace1.name);

    // check names of traces/spans
    await expect(
      page.getByRole("tree").locator("p", { hasText: trace1.name }),
    ).toBeVisible();
    await expect(
      page.getByRole("tree").locator("p", { hasText: span.name }),
    ).toBeVisible();
    await expect(
      page.getByRole("tree").locator("p", { hasText: span.spans[0].name }),
    ).toBeVisible();

    // check duration text
    await expect(
      page.locator("ul.level-0").locator("div", { hasText: "12.5s" }).first(),
    ).toBeVisible();
    await expect(
      page.locator("ul.level-1").locator("div", { hasText: "2.6s" }).first(),
    ).toBeVisible();
    await expect(
      page.locator("ul.level-2").locator("div", { hasText: "2.6s" }).first(),
    ).toBeVisible();

    // check tokens text
    await expect(
      page.locator("ul.level-1").locator("div", { hasText: "225" }).first(),
    ).toBeVisible();
  });

  test("Check selecting trace/span in spans tree", async ({
    page,
    project,
    trace1,
    span,
    tracesPage,
  }) => {
    await trace1.addScore(TRACE_SCORE as FeedbackScoreData);
    await tracesPage.goto(project.id);
    await tracesPage.openSidePanel(trace1.name);

    // check trace data
    await expect(page.getByTestId("data-viewer-title")).toHaveText(trace1.name);
    await expect(page.getByTestId("data-viewer-duration")).toHaveText(
      "12.5 seconds",
    );
    await expect(page.getByTestId("data-viewer-scores")).toHaveText(
      "1 feedback scores",
    );

    await tracesPage.selectSidebarTab("Input/Output");
    await expect(
      tracesPage.sidePanel.container.getByText(TRACE_1.input.prompt),
    ).toBeVisible();
    await expect(
      tracesPage.sidePanel.container.getByText(TRACE_1.output.response),
    ).toBeVisible();

    await tracesPage.selectSidebarTab("Feedback scores");
    await expect(
      tracesPage.sidePanel.container.getByText(TRACE_SCORE.name),
    ).toBeVisible();

    await tracesPage.selectSidebarTab("Metadata");
    await expect(
      tracesPage.sidePanel.container.getByText(TRACE_1.metadata.string),
    ).toBeVisible();

    // check span data
    await page.locator("ul.level-1 button").first().click();
    await expect(page.getByTestId("data-viewer-title")).toHaveText(span.name);
    await expect(page.getByTestId("data-viewer-duration")).toHaveText(
      "2.6 seconds",
    );
    await expect(page.getByTestId("data-viewer-tokens")).toHaveText(
      "225 tokens",
    );

    await tracesPage.selectSidebarTab("Input/Output");
    await expect(
      tracesPage.sidePanel.container.getByText(SPAN_1.output.response),
    ).toBeVisible();

    await tracesPage.selectSidebarTab("Metadata");
    await expect(
      tracesPage.sidePanel.container.getByText(
        String(SPAN_1.usage.prompt_tokens),
      ),
    ).toBeVisible();

    // check sub span data
    await page.locator("ul.level-2 button").first().click();
    await expect(page.getByTestId("data-viewer-title")).toHaveText(
      span.spans[0].name,
    );
    await expect(page.getByTestId("data-viewer-duration")).toHaveText(
      "2.6 seconds",
    );

    await tracesPage.selectSidebarTab("Input/Output");
    await expect(
      tracesPage.sidePanel.container.getByText(SPAN_2.input.prompt),
    ).toBeVisible();
    await expect(
      tracesPage.sidePanel.container.getByText(SPAN_2.output.response),
    ).toBeVisible();

    await tracesPage.selectSidebarTab("Metadata");
    await expect(
      tracesPage.sidePanel.container.getByText(SPAN_2.metadata.string),
    ).toBeVisible();
  });

  test("Check next/previous button", async ({
    page,
    project,
    trace1,
    trace2,
    tracesPage,
  }) => {
    await tracesPage.goto(project.id);
    await tracesPage.openSidePanel(trace1.name);
    await expect(page.getByTestId("data-viewer-title")).toHaveText(trace1.name);

    await tracesPage.sidePanel.previous();
    await expect(page.getByTestId("data-viewer-title")).toHaveText(trace2.name);

    await tracesPage.sidePanel.next();
    await expect(page.getByTestId("data-viewer-title")).toHaveText(trace1.name);
  });

  test("Check delete trace", async ({
    page,
    project,
    span,
    trace2,
    tracesPage,
  }) => {
    await tracesPage.goto(project.id);

    // delete from traces view
    await tracesPage.openSidePanel(trace2.name);
    await tracesPage.sidePanel.container
      .getByRole("button", { name: "Delete" })
      .click();
    await page.getByRole("button", { name: "Delete" }).click();
    await expect(
      tracesPage.table.getRowLocatorByCellText(trace2.name),
    ).not.toBeVisible();

    // delete from LLM calls view
    await tracesPage.switchToLLMCalls();
    await tracesPage.openSidePanel(span.name);
    await tracesPage.sidePanel.container
      .getByRole("button", { name: "Delete" })
      .click();
    await page.getByRole("button", { name: "Delete" }).click();
    await expect(
      tracesPage.table.getRowLocatorByCellText(span.name),
    ).not.toBeVisible();
  });

  test("Check add/delete tags to trace", async ({
    project,
    trace1,
    tracesPage,
  }) => {
    await tracesPage.goto(project.id);

    // add new tag
    await tracesPage.openSidePanel(trace1.name);
    await tracesPage.addTag(TRACE_TAG_NAME);
    await expect(
      tracesPage.sidePanel.container.getByText(TRACE_TAG_NAME),
    ).toBeVisible();
    await tracesPage.sidePanel.close();
    await tracesPage.columns.select("Tags");
    await expect(
      tracesPage.table
        .getRowLocatorByCellText(trace1.name)
        .getByText(TRACE_TAG_NAME),
    ).toBeVisible();

    // delete tag
    await tracesPage.openSidePanel(trace1.name);
    await tracesPage.deleteTag(TRACE_TAG_NAME);

    await expect(
      tracesPage.sidePanel.container.getByText(TRACE_TAG_NAME),
    ).not.toBeVisible();
    await tracesPage.sidePanel.close();
    await expect(
      tracesPage.table
        .getRowLocatorByCellText(trace1.name)
        .getByText(TRACE_TAG_NAME),
    ).not.toBeVisible();
  });

  test("Check add/delete tags to span", async ({
    project,
    span,
    tracesPage,
  }) => {
    await tracesPage.goto(project.id);
    await tracesPage.switchToLLMCalls();

    // add new tag
    await tracesPage.openSidePanel(span.name);
    await tracesPage.addTag(SPAN_TAG_NAME);

    await expect(
      tracesPage.sidePanel.container.getByText(SPAN_TAG_NAME),
    ).toBeVisible();
    await tracesPage.sidePanel.close();
    await tracesPage.columns.select("Tags");
    await expect(
      tracesPage.table
        .getRowLocatorByCellText(span.name)
        .getByText(SPAN_TAG_NAME),
    ).toBeVisible();

    // delete tag
    await tracesPage.openSidePanel(span.name);
    await tracesPage.deleteTag(SPAN_TAG_NAME);

    await expect(
      tracesPage.sidePanel.container.getByText(SPAN_TAG_NAME),
    ).not.toBeVisible();
    await tracesPage.sidePanel.close();
    await expect(
      tracesPage.table
        .getRowLocatorByCellText(span.name)
        .getByText(SPAN_TAG_NAME),
    ).not.toBeVisible();
  });
});
