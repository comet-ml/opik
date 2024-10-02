import { expect, test } from "@e2e/fixtures";

test.describe("Traces table", () => {
  test("Check trace/span creation", async ({
    page,
    project,
    trace1,
    span,
    tracesPage,
  }) => {
    await tracesPage.goto(project.id);
    await expect(tracesPage.title).toBeVisible();
    await expect(page.locator("td").getByText(trace1.name)).toBeVisible();

    await tracesPage.switchToLLMCalls();
    await expect(page.locator("td").getByText(trace1.name)).not.toBeVisible();
    await expect(page.locator("td").getByText(span.name)).toBeVisible();
  });
});
