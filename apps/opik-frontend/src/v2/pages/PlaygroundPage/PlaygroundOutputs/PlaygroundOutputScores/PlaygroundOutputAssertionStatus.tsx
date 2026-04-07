import React, { useRef, useMemo } from "react";

import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import useAppStore from "@/store/AppStore";
import { ExperimentsCompare } from "@/types/datasets";
import {
  StatusTag,
  getStatusFromExperimentItems,
} from "@/v2/pages-shared/experiments/EvaluationSuiteExperiment/PassedCell";

const REFETCH_INTERVAL = 5000;
const MAX_REFETCH_TIME = 300000;

const areAllExperimentItemsScored = (
  matchingRow: ExperimentsCompare,
  datasetItemId: string,
): boolean => {
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
  stale?: boolean;
}

const PlaygroundOutputAssertionStatus: React.FunctionComponent<
  PlaygroundOutputAssertionStatusProps
> = ({ experimentId, datasetItemId, datasetId, stale = false }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const pollingStartTimeRef = useRef<number | null>(null);

  if (pollingStartTimeRef.current === null && experimentId) {
    pollingStartTimeRef.current = Date.now();
  }

  const { data } = useCompareExperimentsList(
    {
      workspaceName,
      datasetId,
      experimentsIds: experimentId ? [experimentId] : [],
      page: 1,
      size: 1000,
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
      return undefined;
    }

    if (!areAllExperimentItemsScored(matchingRow, datasetItemId)) {
      return undefined;
    }

    return getStatusFromExperimentItems(matchingRow);
  }, [data?.content, datasetItemId]);

  if (!experimentId) {
    return null;
  }

  if (!statusInfo?.status) {
    return <span className="text-muted-slate">{"\u2014"}</span>;
  }

  return (
    <StatusTag
      {...statusInfo}
      tagSize="sm"
      className={stale ? "opacity-50" : undefined}
    />
  );
};

export default PlaygroundOutputAssertionStatus;
