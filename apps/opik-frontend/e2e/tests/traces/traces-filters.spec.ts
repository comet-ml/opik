import { test } from "@e2e/fixtures";
import { SPAN_1 } from "@e2e/test-data";

test.describe("Traces filters", () => {
  test("Check string type", async ({ project, trace1, trace2, tracesPage }) => {
    await tracesPage.goto(project.id);
    await tracesPage.filters.applyStringFilter("Name", "=", trace1.name);
    await tracesPage.table.hasRowCount(1);

    await tracesPage.filters.applyStringFilter("Name", "contains", trace1.name);
    await tracesPage.table.hasRowCount(2);

    await tracesPage.filters.applyStringFilter(
      "Name",
      "doesn't contain",
      trace2.name,
    );
    await tracesPage.table.hasRowCount(1);

    await tracesPage.filters.applyStringFilter(
      "Input",
      "starts with",
      `{"prompt"`,
    );
    await tracesPage.table.hasRowCount(2);

    await tracesPage.filters.applyStringFilter("Output", "ends with", `}`);
    await tracesPage.table.hasRowCount(1);
  });

  test("Check number type", async ({
    project,
    // we need to keep trace1 and span to generate data
    trace1,
    span,
    tracesPage,
  }) => {
    await tracesPage.goto(project.id);
    await tracesPage.switchToLLMCalls();

    await tracesPage.filters.applyNumberFilter(
      "Total tokens",
      "=",
      SPAN_1.usage.total_tokens,
    );
    await tracesPage.table.hasRowCount(1);

    await tracesPage.filters.applyNumberFilter(
      "Total input tokens",
      ">",
      SPAN_1.usage.prompt_tokens,
    );
    await tracesPage.table.hasNoData();

    await tracesPage.filters.applyNumberFilter(
      "Total input tokens",
      ">=",
      SPAN_1.usage.prompt_tokens,
    );
    await tracesPage.table.hasRowCount(1);

    await tracesPage.filters.applyNumberFilter(
      "Total output tokens",
      "<",
      SPAN_1.usage.completion_tokens,
    );
    await tracesPage.table.hasNoData();

    await tracesPage.filters.applyNumberFilter(
      "Total output tokens",
      "<=",
      SPAN_1.usage.completion_tokens,
    );
    await tracesPage.table.hasRowCount(1);
  });

  test("Check list type", async ({
    project,
    // we need to keep trace1 and trace2 to generate data
    trace1,
    trace2,
    tracesPage,
  }) => {
    await tracesPage.goto(project.id);

    await tracesPage.filters.applyListFilter("Tags", "contains", "test");
    await tracesPage.table.hasRowCount(2);

    await tracesPage.filters.applyListFilter("Tags", "contains", "e2e");
    await tracesPage.table.hasRowCount(1);
  });

  test("Check dictionary type", async ({
    project,
    // we need to keep trace1 and trace2 to generate data
    trace1,
    trace2,
    tracesPage,
  }) => {
    await tracesPage.goto(project.id);

    await tracesPage.filters.applyDictionaryFilter(
      "Metadata",
      "=",
      "test",
      "true",
    );
    await tracesPage.table.hasRowCount(1);

    await tracesPage.filters.applyDictionaryFilter(
      "Metadata",
      "contains",
      "string",
      "555",
    );
    await tracesPage.table.hasNoData();

    await tracesPage.filters.applyDictionaryFilter(
      "Metadata",
      ">",
      "deep.version",
      "1",
    );
    await tracesPage.table.hasRowCount(1);

    await tracesPage.filters.applyDictionaryFilter(
      "Metadata",
      "<",
      "deep.version",
      "5",
    );
    await tracesPage.table.hasRowCount(1);
  });

  test("Check time type", async ({
    project,
    // we need to keep trace1 and trace2 to generate data
    trace1,
    trace2,
    tracesPage,
  }) => {
    await tracesPage.goto(project.id);

    const day = new Date().getDate();

    await tracesPage.filters.applyTimeFilter("Start time", "=", String(day));
    await tracesPage.table.hasRowCount(1);

    await tracesPage.filters.applyTimeFilter("Start time", ">", String(day));
    await tracesPage.table.hasNoData();

    await tracesPage.filters.applyTimeFilter("Start time", ">=", String(day));
    await tracesPage.table.hasRowCount(1);

    await tracesPage.filters.applyTimeFilter("Start time", "<", String(day));
    await tracesPage.table.hasRowCount(1);

    await tracesPage.filters.applyTimeFilter("Start time", "<=", String(day));
    await tracesPage.table.hasRowCount(2);
  });
});
