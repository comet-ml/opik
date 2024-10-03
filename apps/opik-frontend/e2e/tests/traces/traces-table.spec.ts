import { expect, test } from "@e2e/fixtures";

test.describe("Traces table", () => {
  test("Check trace/span creation", async ({
    project,
    trace1,
    span,
    tracesPage,
  }) => {
    await tracesPage.goto(project.id);
    await expect(tracesPage.title).toBeVisible();
    await tracesPage.table.checkIsExist(trace1.name);

    await tracesPage.switchToLLMCalls();
    await tracesPage.table.checkIsNotExist(trace1.name);
    await tracesPage.table.checkIsExist(span.name);
  });
});
