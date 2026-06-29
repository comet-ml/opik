import React, { useRef, useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";

import { getExperimentById } from "@/api/datasets/useExperimentById";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import { COMPARE_EXPERIMENTS_MAX_PAGE_SIZE } from "@/constants/experiments";
import useAppStore from "@/store/AppStore";
import { isExperimentTerminal } from "@/lib/experiments";
import {
  StatusTag,
  getStatusFromExperimentItems,
} from "@/v2/pages-shared/experiments/TestSuiteExperiment/PassedCell";

type StatusInfo = ReturnType<typeof getStatusFromExperimentItems>;

const REFETCH_INTERVAL = 5000;
const MAX_REFETCH_TIME = 300000;

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
  const [pollingTimedOut, setPollingTimedOut] = useState(false);

  useEffect(() => {
    if (experimentId) {
      pollingStartTimeRef.current = Date.now();
      lastStatusRef.current = undefined;
      setPollingTimedOut(false);
    }
  }, [experimentId]);

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

  useEffect(() => {
    if (!experimentId || experimentFinished) {
      setPollingTimedOut(false);
      return;
    }
    const timer = setTimeout(() => setPollingTimedOut(true), MAX_REFETCH_TIME);
    return () => clearTimeout(timer);
  }, [experimentId, experimentFinished]);

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
      refetchInterval: () => {
        if (!experimentId || experimentFinished) return false;

        const elapsed =
          Date.now() - (pollingStartTimeRef.current || Date.now());
        if (elapsed > MAX_REFETCH_TIME) return false;

        return REFETCH_INTERVAL;
      },
    },
  );

  const effectiveExperimentFinished = experimentFinished || pollingTimedOut;

  const statusInfo = useMemo(() => {
    const items = data?.content ?? [];
    const matchingRow = items.find(
      (item) =>
        item.experiment_items?.some(
          (ei) => ei.dataset_item_id === datasetItemId,
        ),
    );

    if (!matchingRow) return lastStatusRef.current;

    const result = getStatusFromExperimentItems(
      matchingRow,
      effectiveExperimentFinished,
    );
    lastStatusRef.current = result;
    return result;
  }, [data?.content, datasetItemId, effectiveExperimentFinished]);

  if (!experimentId) return null;
  if (!statusInfo?.status && !statusInfo?.evaluating) return null;

  return <StatusTag {...statusInfo} />;
};

export default PlaygroundOutputAssertionStatus;
