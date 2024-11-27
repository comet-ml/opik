import { expect, test } from "@e2e/fixtures";
import { SPAN_1, TRACE_SCORE } from "@e2e/test-data";
import { FeedbackScoreData } from "@e2e/entities";

test.describe("Traces table", () => {
  test("Check data visibility", async ({
    project,
    trace1,
    span,
    tracesPage,
  }) => {
    await trace1.addScore(TRACE_SCORE as FeedbackScoreData);
    await tracesPage.goto(project.id);
    await expect(tracesPage.title).toBeVisible();
    await tracesPage.columns.selectAll();
    const timeFormat =
      /^(0[1-9]|1[0-2])\/(0[1-9]|[12][0-9]|3[01])\/\d{2} (0[1-9]|1[0-2]):[0-5][0-9] (AM|PM)$/;

    // test traces visibility
    const traceData = trace1.original as {
      id: string;
      input: object;
      output: object;
      metadata: object;
      tags: string[];
    };

    await expect(
      tracesPage.table.getCellLocatorByCellId(trace1.name, "id"),
    ).toHaveText(`${traceData.id.slice(0, 6)}...`);

    await expect(
      tracesPage.table.getCellLocatorByCellId(trace1.name, "start_time"),
    ).toHaveText(timeFormat);

    await expect(
      tracesPage.table.getCellLocatorByCellId(trace1.name, "end_time"),
    ).toHaveText(timeFormat);

    await expect(
      tracesPage.table.getCellLocatorByCellId(trace1.name, "input"),
    ).toHaveText(JSON.stringify(traceData.input, null, 2));

    await expect(
      tracesPage.table.getCellLocatorByCellId(trace1.name, "output"),
    ).toHaveText(JSON.stringify(traceData.output, null, 2));

    await expect(
      tracesPage.table.getCellLocatorByCellId(trace1.name, "metadata"),
    ).toHaveText(JSON.stringify(traceData.metadata, null, 2));

    await expect(
      tracesPage.table.getCellLocatorByCellId(
        trace1.name,
        `feedback_scores_${TRACE_SCORE.name}`,
      ),
    ).toHaveText(`${TRACE_SCORE.value}`);

    await expect(
      tracesPage.table.getCellLocatorByCellId(trace1.name, "tags"),
    ).toHaveText(traceData.tags.sort().join(""));

    await expect(
      tracesPage.table.getCellLocatorByCellId(
        trace1.name,
        "usage_total_tokens",
      ),
    ).toHaveText(String(SPAN_1.usage.total_tokens));

    await expect(
      tracesPage.table.getCellLocatorByCellId(
        trace1.name,
        "usage_prompt_tokens",
      ),
    ).toHaveText(String(SPAN_1.usage.prompt_tokens));

    await expect(
      tracesPage.table.getCellLocatorByCellId(
        trace1.name,
        "usage_completion_tokens",
      ),
    ).toHaveText(String(SPAN_1.usage.completion_tokens));

    await tracesPage.switchToLLMCalls();
    await tracesPage.table.checkIsNotExist(trace1.name);

    // test spans visibility
    const spanData = span.original as {
      id: string;
      name: string;
      output: object;
      tags: string[];
    };

    await expect(
      tracesPage.table.getCellLocatorByCellId(span.name, "id"),
    ).toHaveText(`${spanData.id.slice(0, 6)}...`);

    await expect(
      tracesPage.table.getCellLocatorByCellId(span.name, "start_time"),
    ).toHaveText(timeFormat);

    await expect(
      tracesPage.table.getCellLocatorByCellId(span.name, "end_time"),
    ).toHaveText(timeFormat);

    await expect(
      tracesPage.table.getCellLocatorByCellId(span.name, "output"),
    ).toHaveText(JSON.stringify(spanData.output, null, 2));

    await expect(
      tracesPage.table.getCellLocatorByCellId(span.name, "tags"),
    ).toHaveText(spanData.tags.sort().join(""));

    await expect(
      tracesPage.table.getCellLocatorByCellId(span.name, "usage_total_tokens"),
    ).toHaveText(String(SPAN_1.usage.total_tokens));

    await expect(
      tracesPage.table.getCellLocatorByCellId(span.name, "usage_prompt_tokens"),
    ).toHaveText(String(SPAN_1.usage.prompt_tokens));

    await expect(
      tracesPage.table.getCellLocatorByCellId(
        span.name,
        "usage_completion_tokens",
      ),
    ).toHaveText(String(SPAN_1.usage.completion_tokens));
  });
});
