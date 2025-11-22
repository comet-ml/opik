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

  test("Blank text is blocked", async ({ project, trace1, tracesPage }) => {
    await tracesPage.goto(project.id);
    await tracesPage.table.getRowLocatorByCellText(trace1.name).locator('input[type="checkbox"]').check();
    await tracesPage.page.getByRole("button", { name: "Comment" }).click();
    await tracesPage.page.getByPlaceholder("Comment text").fill("   ");
    await expect(tracesPage.page.getByRole("button", { name: "Add comments" })).toBeDisabled();
  });

  test("Selecting over 10 traces is blocked", async ({ project, tracesPage }) => {
    await tracesPage.goto(project.id);

    for (let i = 0; i < 11; i++) {
      await tracesPage.page.getByRole("button", { name: "Add trace" }).click();
      await tracesPage.page.getByPlaceholder("Name").fill(`t-${i}`);
      await tracesPage.page.getByRole("button", { name: "Create" }).click();
    }

    const rows = await tracesPage.table.tBody.locator('tr').all();
    for (let i = 0; i < 11; i++) {
      await rows[i].locator('input[type="checkbox"]').check();
    }

    await tracesPage.page.getByRole("button", { name: "Comment" }).click();
    await tracesPage.page.getByPlaceholder("Comment text").fill("over-limit");
    await expect(tracesPage.page.getByRole("button", { name: "Add comments" })).toBeDisabled();
    await expect(tracesPage.page.getByText("You can comment on up to 10 items at a time.")).toBeVisible();
  });
});

