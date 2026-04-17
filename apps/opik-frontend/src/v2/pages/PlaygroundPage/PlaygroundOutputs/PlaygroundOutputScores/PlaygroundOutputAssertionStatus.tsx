import React, { useRef, useEffect, useMemo } from "react";

import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import { COMPARE_EXPERIMENTS_MAX_PAGE_SIZE } from "@/constants/experiments";
import useAppStore from "@/store/AppStore";
import { ExperimentsCompare } from "@/types/datasets";
import { extractAssertions } from "@/lib/assertion-converters";
import {
  StatusTag,
  getStatusFromExperimentItems,
} from "@/v2/pages-shared/experiments/TestSuiteExperiment/PassedCell";

type StatusInfo = ReturnType<typeof getStatusFromExperimentItems>;

const REFETCH_INTERVAL = 5000;
const MAX_REFETCH_TIME = 300000;

const hasNoAssertions = (row: ExperimentsCompare): boolean => {
  const evaluators = row.evaluators;
  if (!evaluators) return false;
  return extractAssertions(evaluators).length === 0;
};

const areAllExperimentItemsScored = (
  matchingRow: ExperimentsCompare,
  datasetItemId: string,
): boolean => {
  if (hasNoAssertions(matchingRow)) return true;

  const relatedItems =
    matchingRow.experiment_items?.filter(
      (ei) => ei.dataset_item_id === datasetItemId,
    ) ?? [];
  return (
    relatedItems.length > 0 && relatedItems.every((ei) => ei.status != null)
  );
};

interface PlaygroundOutputAssertionStatusProps {
  experimentId: string | undefined;
  datasetItemId: string;
  datasetId: string;
}

const PlaygroundOutputAssertionStatus: React.FunctionComponent<
  PlaygroundOutputAssertionStatusProps
> = ({ experimentId, datasetItemId, datasetId }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const pollingStartTimeRef = useRef<number | null>(null);
  const lastStatusRef = useRef<StatusInfo | undefined>(undefined);

  useEffect(() => {
    if (experimentId) {
      pollingStartTimeRef.current = Date.now();
      lastStatusRef.current = undefined;
    }
  }, [experimentId]);

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
        if (!experimentId) return false;

        const elapsed =
          Date.now() - (pollingStartTimeRef.current || Date.now());
        if (elapsed > MAX_REFETCH_TIME) return false;

        const items = query.state.data?.content ?? [];
        const matchingRow = items.find(
          (item) =>
            item.experiment_items?.some(
              (ei) => ei.dataset_item_id === datasetItemId,
            ),
        );

        if (!matchingRow) return REFETCH_INTERVAL;

        if (areAllExperimentItemsScored(matchingRow, datasetItemId))
          return false;

        return REFETCH_INTERVAL;
      },
    },
  );

  const statusInfo = useMemo(() => {
    const items = data?.content ?? [];
    const matchingRow = items.find(
      (item) =>
        item.experiment_items?.some(
          (ei) => ei.dataset_item_id === datasetItemId,
        ),
    );

    if (!matchingRow) {
      return lastStatusRef.current;
    }

    const result = getStatusFromExperimentItems(matchingRow);
    lastStatusRef.current = result;
    return result;
  }, [data?.content, datasetItemId]);

  if (!experimentId) {
    return null;
  }

  if (!statusInfo?.status && !statusInfo?.evaluating) {
    return null;
  }

  return <StatusTag {...statusInfo} />;
};

export default PlaygroundOutputAssertionStatus;
