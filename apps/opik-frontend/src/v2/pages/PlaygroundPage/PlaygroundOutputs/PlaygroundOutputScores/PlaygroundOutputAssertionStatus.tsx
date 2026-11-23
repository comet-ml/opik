import React, { useRef, useMemo, useState } from "react";
import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";

import { getExperimentById } from "@/api/datasets/useExperimentById";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import { COMPARE_EXPERIMENTS_MAX_PAGE_SIZE } from "@/constants/experiments";
import {
  getOnlineScoringStatuses,
  onlineScoringStatusesKey,
  UseOnlineScoringStatusesResponse,
} from "@/api/automations/useOnlineScoringStatuses";
import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import { OnlineScoringStatus } from "@/types/automations";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useAppStore from "@/store/AppStore";
import { isExperimentTerminal } from "@/lib/experiments";
import {
  ERROR_RESULT,
  StatusTag,
  getStatusFromExperimentItems,
} from "@/v2/pages-shared/experiments/TestSuiteExperiment/PassedCell";
import {
  REFETCH_INTERVAL,
  isFinalStatus,
  isRetrying,
  isRetryingRecently,
  lateWaitEndOf,
  scoringPollInterval,
  scoringStatusMessage,
  useWaitOver,
  waitEndOf,
} from "./scoringStatus";
import { useSuiteAssertionCount } from "../suiteAssertions";

type StatusInfo = ReturnType<typeof getStatusFromExperimentItems>;

// One experiment item per run of the dataset item.
const runsOf = (
  rows: ExperimentsCompare[] | undefined,
  datasetItemId: string,
) =>
  (rows ?? [])
    .flatMap((row) => row.experiment_items ?? [])
    .filter((item) => item.dataset_item_id === datasetItemId);

// With several runs per item, the run written last is the one whose judges started last, so the wait counts from it.
const lastWrittenAtOf = (runs: ExperimentItem[]) =>
  runs
    .map((run) => run.created_at)
    .filter(Boolean)
    .sort()
    .at(-1);

// Project rules can score the same trace, so only the suite's judges speak for the item. A trace has one judge
// per suite-level and per item-level set of assertions.
const judgeStatusesOf = (
  statuses: UseOnlineScoringStatusesResponse | undefined,
) => (statuses?.content ?? []).filter((status) => status.suite_assertion);

const anyRetryingRecently = (statuses: OnlineScoringStatus[]) =>
  statuses.some(isRetryingRecently);

interface PlaygroundOutputAssertionStatusProps {
  experimentId: string | undefined;
  datasetItemId: string;
  datasetId: string;
}

const PlaygroundOutputAssertionStatus: React.FunctionComponent<
  PlaygroundOutputAssertionStatusProps
> = ({ experimentId, datasetItemId, datasetId }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const queryClient = useQueryClient();
  const [openedAt] = useState(Date.now);
  const lastStatusRef = useRef<StatusInfo | undefined>(undefined);

  const { data: experimentData } = useQuery({
    queryKey: ["experiment", { experimentId }],
    queryFn: (context) =>
      getExperimentById(context, { experimentId: experimentId! }),
    enabled: !!experimentId,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (isExperimentTerminal(status)) {
        return false;
      }
      return REFETCH_INTERVAL;
    },
  });

  const experimentFinished = isExperimentTerminal(experimentData?.status);
  const suiteAssertionCount = useSuiteAssertionCount(datasetId, experimentData);
  // Until the version is known, assume it has assertions rather than call the row skipped.
  const suiteHasAssertions =
    suiteAssertionCount === undefined || suiteAssertionCount > 0;

  const { data } = useCompareExperimentsList(
    {
      workspaceName,
      datasetId,
      experimentsIds: experimentId ? [experimentId] : [],
      page: 1,
      // TODO: OPIK-5724 — add BE dataset_item_id filter so we can fetch size: 1
      size: COMPARE_EXPERIMENTS_MAX_PAGE_SIZE,
      truncate: true,
    },
    {
      enabled: !!experimentId && !!datasetId,
      refetchInterval: (query) => {
        if (!experimentId || experimentFinished) return false;
        const runs = runsOf(query.state.data?.content, datasetItemId);
        return scoringPollInterval({
          // The item is written once its run has finished, so the wait counts from then.
          waitEnd: waitEndOf(lastWrittenAtOf(runs), openedAt),
          lateWaitEnd: lateWaitEndOf(lastWrittenAtOf(runs), openedAt),
          // A retrying judge ends in a result once the stream gives up, so keep looking for it. The
          // statuses are queried below, as they need these rows, so read them from the cache.
          retrying: runs.some(
            (run) =>
              !!run.trace_id &&
              anyRetryingRecently(
                judgeStatusesOf(
                  queryClient.getQueryData<UseOnlineScoringStatusesResponse>(
                    onlineScoringStatusesKey(run.trace_id),
                  ),
                ),
              ),
          ),
          // The experiment is still running, so its results are still owed.
          owed: true,
        });
      },
    },
  );

  const runs = useMemo(
    () => runsOf(data?.content, datasetItemId),
    [data?.content, datasetItemId],
  );
  const waitEnd = waitEndOf(lastWrittenAtOf(runs), openedAt);
  const lateWaitEnd = lateWaitEndOf(lastWrittenAtOf(runs), openedAt);
  const traceIds = runs.flatMap((run) => (run.trace_id ? [run.trace_id] : []));
  const unscoredTraceIds = new Set(
    runs.flatMap((run) =>
      run.trace_id && !run.assertion_results?.length ? [run.trace_id] : [],
    ),
  );

  const judgeStatusesByTraceId = useQueries({
    queries: traceIds.map((traceId) => ({
      queryKey: onlineScoringStatusesKey(traceId),
      queryFn: (context: Parameters<typeof getOnlineScoringStatuses>[0]) =>
        getOnlineScoringStatuses(context, { traceId }),
      // Once the run is over, only a run still without results needs its judges, to say why.
      enabled: !experimentFinished || unscoredTraceIds.has(traceId),
      refetchInterval: (query: {
        state: { data?: UseOnlineScoringStatusesResponse };
      }) => {
        if (experimentFinished) return false;
        return scoringPollInterval({
          waitEnd,
          lateWaitEnd,
          retrying: anyRetryingRecently(judgeStatusesOf(query.state.data)),
          owed: true,
        });
      },
    })),
    combine: (results) =>
      Object.fromEntries(
        results.map((result, index) => [
          traceIds[index],
          judgeStatusesOf(result.data),
        ]),
      ) as Record<string, OnlineScoringStatus[]>,
  });

  // A run that has its assertion results needs nothing more from its judges.
  const unscoredRunStatuses = runs
    .filter((run) => !run.assertion_results?.length)
    .map((run) =>
      run.trace_id ? (judgeStatusesByTraceId[run.trace_id] ?? []) : [],
    );
  // Any judge still retrying may yet produce a result, so it speaks for the item first. A judge that scored clears
  // its status, so this holds for runs that already have some results too.
  const retryingJudgeStatus = traceIds
    .flatMap((traceId) => judgeStatusesByTraceId[traceId] ?? [])
    .find((status) => isRetrying(status));
  // The item has no result coming only once every run still without one has judges, and all of them gave up.
  const finalJudgeStatus =
    !retryingJudgeStatus &&
    unscoredRunStatuses.length > 0 &&
    unscoredRunStatuses.every(
      (statuses) => statuses.length > 0 && statuses.every(isFinalStatus),
    )
      ? unscoredRunStatuses[0][0]
      : undefined;

  const waitOver = useWaitOver(waitEnd);

  // A retrying judge has said why there is no result yet, so the wait for one does not apply to it.
  const effectiveExperimentFinished =
    experimentFinished || (waitOver && !retryingJudgeStatus);

  const statusInfo = useMemo(() => {
    const items = data?.content ?? [];
    const matchingRow = items.find((item) =>
      item.experiment_items?.some((ei) => ei.dataset_item_id === datasetItemId),
    );

    if (!matchingRow) return lastStatusRef.current;

    const result = getStatusFromExperimentItems(
      matchingRow,
      effectiveExperimentFinished,
      suiteHasAssertions,
    );
    lastStatusRef.current = result;
    return result;
  }, [
    data?.content,
    datasetItemId,
    effectiveExperimentFinished,
    suiteHasAssertions,
  ]);

  if (!experimentId) return null;

  // Once the run is over, a row without results is a generic scoring error; its judges know why.
  if ((statusInfo?.evaluating || statusInfo?.isError) && finalJudgeStatus) {
    return (
      <StatusTag {...ERROR_RESULT(scoringStatusMessage(finalJudgeStatus))} />
    );
  }

  if (statusInfo?.evaluating && retryingJudgeStatus) {
    return (
      <TooltipWrapper content={scoringStatusMessage(retryingJudgeStatus)}>
        <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-xs text-muted-slate">
          <RefreshCw className="size-3 text-chart-yellow" />
          Scoring failed, retrying
        </span>
      </TooltipWrapper>
    );
  }

  if (!statusInfo?.status && !statusInfo?.evaluating) return null;

  return <StatusTag {...statusInfo} />;
};

export default PlaygroundOutputAssertionStatus;
