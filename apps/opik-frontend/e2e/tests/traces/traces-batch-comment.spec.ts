import { expect, test } from "@e2e/fixtures";

test.describe("Traces batch comment", () => {
  test("Add batch comment to traces", async ({ project, trace1, trace2, tracesPage }) => {
    await tracesPage.goto(project.id);

    await tracesPage.table.getRowLocatorByCellText(trace1.name).locator('input[type="checkbox"]').check();
    await tracesPage.table.getRowLocatorByCellText(trace2.name).locator('input[type="checkbox"]').check();

    await tracesPage.page.getByRole("button", { name: "Comment" }).click();
    await tracesPage.page.getByPlaceholder("Comment text").fill("batch-comment");
    await tracesPage.page.getByRole("button", { name: "Add comments" }).click();

    await tracesPage.table.openRowActionsByCellText(trace1.name);
    await tracesPage.page.getByRole("menuitem", { name: "Open" }).click();
    await tracesPage.selectSidebarTab("Comments");
    await expect(tracesPage.sidePanel.container.getByText("batch-comment")).toBeVisible();

    await tracesPage.page.getByRole("button", { name: "Close" }).click();

    await tracesPage.table.openRowActionsByCellText(trace2.name);
    await tracesPage.page.getByRole("menuitem", { name: "Open" }).click();
    await tracesPage.selectSidebarTab("Comments");
    await expect(tracesPage.sidePanel.container.getByText("batch-comment")).toBeVisible();
  });

  test("Disabled for spans", async ({ project, trace1, span, tracesPage }) => {
    await tracesPage.goto(project.id);
    await tracesPage.switchToLLMCalls();
    await tracesPage.table.getRowLocatorByCellText(span.name).locator('input[type="checkbox"]').check();
    await expect(tracesPage.page.getByRole("button", { name: "Comment" })).toBeDisabled();
  });
});

