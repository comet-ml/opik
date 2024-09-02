import { expect, test } from "@e2e/fixtures";

test.describe("Traces table", () => {
  test("Check trace/span creation", async ({
    page,
    project,
    projectTrace,
    span,
    tracesPage,
  }) => {
    await tracesPage.goto(project.id);
    await expect(tracesPage.title).toBeVisible();
    await expect(page.locator("td").getByText(projectTrace.name)).toBeVisible();

    tracesPage.switchToLLMCalls();
    await expect(
      page.locator("td").getByText(projectTrace.name),
    ).not.toBeVisible();
    await expect(page.locator("td").getByText(span.name)).toBeVisible();
  });
});
