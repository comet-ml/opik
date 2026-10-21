import { useState } from "react";
import useTracesList from "@/api/traces/useTracesList";
import { COLUMN_TYPE } from "@/types/shared";
import { Filter } from "@/types/filters";
import { Trace } from "@/types/traces";

export const PREVIEW_SAMPLE_SIZE = 3;

type UseAnnotatorPreviewSamplesParams = {
  projectId: string;
  queueId?: string;
  isThreadScope: boolean;
};

const useAnnotatorPreviewSamples = ({
  projectId,
  queueId,
  isThreadScope,
}: UseAnnotatorPreviewSamplesParams) => {
  const [sampleIndex, setSampleIndex] = useState(0);
  const sampleParams = {
    projectId,
    page: 1,
    size: PREVIEW_SAMPLE_SIZE,
    truncate: false,
  };
  const useQueueSample = Boolean(queueId) && !isThreadScope;
  const queueFilters: Filter[] = [
    {
      id: "annotation-queue",
      field: "annotation_queue_ids",
      type: COLUMN_TYPE.list,
      operator: "contains",
      value: queueId ?? "",
    },
  ];
  const { data: queueTraces, isPending: isQueuePending } = useTracesList(
    { ...sampleParams, filters: queueFilters },
    { enabled: useQueueSample },
  );
  const hasQueueSamples = Boolean(queueTraces?.content.length);
  const useProjectSample =
    !useQueueSample || (!isQueuePending && !hasQueueSamples);
  const { data: projectTraces, isPending: isProjectPending } = useTracesList(
    sampleParams,
    { enabled: Boolean(projectId) && useProjectSample },
  );

  const samples: Trace[] =
    useQueueSample && hasQueueSamples
      ? queueTraces?.content ?? []
      : projectTraces?.content ?? [];

  const currentIndex = Math.min(sampleIndex, Math.max(samples.length - 1, 0));

  return {
    samples,
    sample: samples[currentIndex],
    sampleIndex: currentIndex,
    setSampleIndex,
    isPending:
      (useQueueSample && isQueuePending) ||
      (useProjectSample && isProjectPending),
  };
};

export default useAnnotatorPreviewSamples;
