import { useMemo, useState } from "react";
import useThreadList from "@/api/traces/useThreadsList";
import useTracesList from "@/api/traces/useTracesList";
import { COLUMN_TYPE } from "@/types/shared";
import { Thread } from "@/types/traces";
import { useVisibleTraces } from "@/v2/pages-shared/traces/hiddenSpans";
import { PREVIEW_SAMPLE_SIZE } from "./useAnnotatorTracePreviewSamples";

const MAX_THREAD_TRACES = 1000;

const NO_THREADS: Thread[] = [];

type UseAnnotatorThreadPreviewSamplesParams = {
  projectId: string;
  queueId?: string;
  enabled: boolean;
};

const useAnnotatorThreadPreviewSamples = ({
  projectId,
  queueId,
  enabled,
}: UseAnnotatorThreadPreviewSamplesParams) => {
  const [sampleIndex, setSampleIndex] = useState(0);
  const sampleParams = {
    projectId,
    page: 1,
    size: PREVIEW_SAMPLE_SIZE,
    truncate: false,
  };
  const useQueueSample = enabled && Boolean(queueId);
  const { data: queueThreads, isPending: isQueuePending } = useThreadList(
    { ...sampleParams, annotationQueueId: queueId },
    { enabled: useQueueSample },
  );
  const hasQueueSamples = Boolean(queueThreads?.content.length);
  const useProjectSample =
    enabled && (!useQueueSample || (!isQueuePending && !hasQueueSamples));
  const { data: projectThreads, isPending: isProjectPending } = useThreadList(
    sampleParams,
    {
      enabled: Boolean(projectId) && useProjectSample,
    },
  );

  const fromQueue = useQueueSample && hasQueueSamples;
  const threads: Thread[] =
    (fromQueue ? queueThreads?.content : projectThreads?.content) ?? NO_THREADS;

  const currentIndex = Math.min(sampleIndex, Math.max(threads.length - 1, 0));
  const thread = threads[currentIndex];

  const { data: tracesData } = useTracesList(
    {
      projectId,
      filters: [
        {
          id: "",
          field: "thread_id",
          type: COLUMN_TYPE.string,
          operator: "=",
          value: thread?.id ?? "",
        },
      ],
      page: 1,
      size: MAX_THREAD_TRACES,
      truncate: false,
    },
    { enabled: Boolean(thread?.id) },
  );
  const traces = useMemo(
    () =>
      [...(tracesData?.content ?? [])].sort((t1, t2) =>
        t1.id.localeCompare(t2.id),
      ),
    [tracesData],
  );
  const visibleTraces = useVisibleTraces(traces);

  return {
    threads,
    thread,
    traces: tracesData ? visibleTraces : undefined,
    sampleIndex: currentIndex,
    setSampleIndex,
    fromProject: !fromQueue,
    isPending:
      (useQueueSample && isQueuePending) ||
      (useProjectSample && isProjectPending),
  };
};

export default useAnnotatorThreadPreviewSamples;
