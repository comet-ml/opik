import { useMemo, useState, useCallback } from "react";
import { useSearch } from "@tanstack/react-router";
import { keepPreviousData } from "@tanstack/react-query";
import useAnnotationQueueById from "@/api/annotation-queues/useAnnotationQueueById";
import useTracesList from "@/api/traces/useTracesList";
import useThreadsList from "@/api/traces/useThreadsList";
import {
  ANNOTATION_QUEUE_SCOPE,
  AnnotationQueue,
} from "@/types/annotation-queues";
import { Thread, Trace } from "@/types/traces";

export enum WORKFLOW_STATUS {
  INITIAL = "initial",
  ANNOTATING = "annotating",
  COMPLETED = "completed",
}

const MAX_QUEUE_ITEMS = 15000;

export const isItemProcessed = (
  item: Trace | Thread,
  feedbackScoreNames: string[],
): boolean => {
  return (item.feedback_scores || []).some((score) =>
    feedbackScoreNames.includes(score.name),
  );
};

export interface AnnotationWorkflowState {
  // Data
  annotationQueue: AnnotationQueue | null;
  queueItems: (Trace | Thread)[];
  unprocessedItems: (Trace | Thread)[];
  processedCount: number;
  currentItem: (Trace | Thread) | undefined;
  currentIndex: number;

  // Status
  canStartAnnotation: boolean;
  isItemsLoading: boolean;
  isLoading: boolean;
  isError: boolean;

  // Actions
  setStatus: (status: WORKFLOW_STATUS) => void;
  handlePrevious: () => void;
  handleSkip: () => void;
  handleSubmitNext: () => void;
  handleStartAnnotating: () => void;
}

export const useAnnotationWorkflow = (): AnnotationWorkflowState => {
  const search = useSearch({ strict: false }) as { queueId?: string };
  const { queueId } = search;

  const [, setStatus] = useState<WORKFLOW_STATUS>(WORKFLOW_STATUS.INITIAL);

  const {
    data: annotationQueue,
    isLoading,
    isError,
  } = useAnnotationQueueById(
    {
      annotationQueueId: queueId!,
    },
    {
      enabled: !!queueId,
    },
  );

  // Create annotation queue filter
  const annotationQueueFilter = useMemo(() => {
    if (!annotationQueue?.id) return [];
    return [
      // createFilter({ // TODO lala uncomment when is implemented
      //   field: "annotation_queue_ids",
      //   value: annotationQueue.id,
      //   operator: "contains",
      // }),
    ];
  }, [annotationQueue?.id]);

  // Fetch associated items based on queue scope
  const { data: tracesData, isLoading: isTracesLoading } = useTracesList(
    {
      projectId: annotationQueue?.project_id || "",
      page: 1,
      size: MAX_QUEUE_ITEMS,
      sorting: [],
      filters: annotationQueueFilter,
      search: "",
      truncate: true,
    },
    {
      enabled:
        !!annotationQueue &&
        !!annotationQueue.project_id &&
        annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.TRACE,
      placeholderData: keepPreviousData,
    },
  );

  const { data: threadsData, isLoading: isThreadsLoading } = useThreadsList(
    {
      projectId: annotationQueue?.project_id || "",
      page: 1,
      size: MAX_QUEUE_ITEMS,
      sorting: [],
      filters: annotationQueueFilter,
      search: "",
    },
    {
      enabled:
        !!annotationQueue &&
        !!annotationQueue.project_id &&
        annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.THREAD,
      placeholderData: keepPreviousData,
    },
  );

  const queueItems = useMemo(() => {
    return (
      (annotationQueue?.scope === ANNOTATION_QUEUE_SCOPE.TRACE
        ? tracesData?.content
        : threadsData?.content) ?? []
    );
  }, [annotationQueue?.scope, tracesData?.content, threadsData?.content]);

  const { unprocessedItems, processedCount, canStartAnnotation } =
    useMemo(() => {
      const feedbackScoreNames =
        annotationQueue?.feedback_definition_names ?? [];
      const unprocessedItems = queueItems.filter(
        (item) => !isItemProcessed(item, feedbackScoreNames),
      );
      const processedCount = queueItems.length - unprocessedItems.length;

      return {
        unprocessedItems,
        processedCount,
        canStartAnnotation: unprocessedItems.length > 0,
      };
    }, [annotationQueue?.feedback_definition_names, queueItems]);

  const [currentIndex, setCurrentIndex] = useState(0);

  const currentItem = useMemo(() => {
    return unprocessedItems[currentIndex] || undefined;
  }, [unprocessedItems, currentIndex]);

  const handlePrevious = useCallback(() => {
    setCurrentIndex((prev) => Math.max(0, prev - 1));
  }, []);

  const handleSkip = useCallback(() => {
    const nextIndex = currentIndex + 1;

    // If we've skipped all items, move to completed state
    if (nextIndex >= unprocessedItems.length) {
      setStatus(WORKFLOW_STATUS.COMPLETED);
    } else {
      setCurrentIndex(nextIndex);
    }
  }, [currentIndex, unprocessedItems.length]);

  const handleSubmitNext = useCallback(() => {
    // Move to next item
    const nextIndex = currentIndex + 1;

    // If we've processed all items, move to completed state
    if (nextIndex >= unprocessedItems.length) {
      setStatus(WORKFLOW_STATUS.COMPLETED);
    } else {
      setCurrentIndex(nextIndex);
    }
  }, [currentIndex, unprocessedItems.length]);

  const handleStartAnnotating = useCallback(() => {
    setStatus(WORKFLOW_STATUS.ANNOTATING);
  }, []);

  const isItemsLoading = isTracesLoading || isThreadsLoading;

  return {
    // Data
    annotationQueue: annotationQueue || null,
    queueItems,
    unprocessedItems,
    processedCount,
    currentItem,
    currentIndex,

    // Status
    canStartAnnotation,
    isItemsLoading,
    isLoading,
    isError,

    // Actions
    setStatus,
    handlePrevious,
    handleSkip,
    handleSubmitNext,
    handleStartAnnotating,
  };
};
