import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import { RunStatus } from "@/types/test-suites";

const shouldSuppressSkippedStatus = (
  row: ExperimentsCompare,
  status: RunStatus | undefined,
) => status === RunStatus.SKIPPED && (row.evaluators?.length ?? 0) > 0;

export const resolvePassedStatus = (
  row: ExperimentsCompare,
  items: ExperimentItem[],
  experimentId?: string,
) => {
  if (experimentId) {
    const status =
      row.run_summaries_by_experiment?.[experimentId]?.status ??
      items[0]?.status;
    return shouldSuppressSkippedStatus(row, status) ? undefined : status;
  }

  const summaries = Object.values(row.run_summaries_by_experiment ?? {});
  let status: RunStatus | undefined;

  if (summaries.length > 0) {
    if (summaries.every((s) => s.status === RunStatus.PASSED)) {
      status = RunStatus.PASSED;
    } else if (summaries.every((s) => s.status === RunStatus.SKIPPED)) {
      status = RunStatus.SKIPPED;
    } else {
      status = RunStatus.FAILED;
    }
  } else {
    status = items[0]?.status;
  }

  return shouldSuppressSkippedStatus(row, status) ? undefined : status;
};

export const processPassedExportColumn = (
  row: ExperimentsCompare,
  items: ExperimentItem[],
  accumulator: Record<string, unknown>,
  prefix: string = "",
  experimentId?: string,
) => {
  const status = resolvePassedStatus(row, items, experimentId);
  accumulator[`${prefix}status`] = status ?? "-";

  items
    .flatMap((item) => item.assertion_results ?? [])
    .forEach((ar, index) => {
      const idx = index + 1;
      accumulator[`${prefix}assertion_${idx}.name`] = ar.value;
      accumulator[`${prefix}assertion_${idx}.result`] = ar.passed
        ? "passed"
        : "failed";
      if (ar.reason) {
        accumulator[`${prefix}assertion_${idx}.reason`] = ar.reason;
      }
    });
};
