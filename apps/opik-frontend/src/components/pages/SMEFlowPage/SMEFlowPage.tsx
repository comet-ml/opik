import React, { useState, useMemo } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { useSearch } from "@tanstack/react-router";
import { keepPreviousData } from "@tanstack/react-query";
import useAnnotationQueueById from "@/api/annotation-queues/useAnnotationQueueById";
import useTracesList from "@/api/traces/useTracesList";
import useThreadsList from "@/api/traces/useThreadsList";
import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";
import { Thread, Trace } from "@/types/traces";
import Loader from "@/components/shared/Loader/Loader";
import NoDataView from "./NoDataView";
import GetStartedView from "./GetStartedView/GetStartedView";
import AnnotationView from "./AnnotationView/AnnotationView";
import CompletionView from "./CompletionView/CompletionView";
import AnnotatingHeader from "@/components/pages/SMEFlowPage/AnnotatingHeader";
import { Button } from "@/components/ui/button";
import { Info } from "lucide-react";

export enum WORKFLOW_STATUS {
  INITIAL = "initial",
  ANNOTATING = "annotating",
  COMPLETED = "completed",
}

const isItemProcessed = (
  item: Trace | Thread,
  feedbackScoreNames: string[],
): boolean => {
  return (item.feedback_scores || []).some((score) =>
    feedbackScoreNames.includes(score.name),
  );
};

const MAX_QUEUE_ITEMS = 15000;

const SMEFlowPage: React.FunctionComponent = () => {
  const [status = WORKFLOW_STATUS.INITIAL, setStatus] = useQueryParam(
    "status",
    StringParam,
    {
      updateType: "replaceIn",
    },
  ) as [WORKFLOW_STATUS, (status: WORKFLOW_STATUS) => void];

  const search = useSearch({ strict: false }) as { queueId?: string };
  const { queueId } = search;

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

  const annotationQueueFilter = useMemo(() => {
    if (!annotationQueue?.id) return [];
    return [];
  }, [annotationQueue?.id]);

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

  const { unprocessedItems, processedCount, totalCount, canStartAnnotation } =
    useMemo(() => {
      const feedbackScoreNames =
        annotationQueue?.feedback_definition_names ?? [];
      const unprocessedItems = queueItems.filter(
        (item) => !isItemProcessed(item, feedbackScoreNames),
      );
      const totalCount = unprocessedItems.length;
      const processedCount = totalCount - unprocessedItems.length;

      return {
        unprocessedItems,
        processedCount,
        totalCount,
        canStartAnnotation: unprocessedItems.length > 0,
      };
    }, [annotationQueue?.feedback_definition_names, queueItems]);

  const isItemsLoading = isTracesLoading || isThreadsLoading;

  const [currentIndex, setCurrentIndex] = useState(0);

  const currentItem = useMemo(() => {
    return queueItems[currentIndex] || undefined;
  }, [queueItems, currentIndex]);

  if (isLoading || isItemsLoading) {
    return (
      <Loader
        message="Loading annotation queue..."
        className="min-h-96 w-full"
      />
    );
  }

  if (!annotationQueue || isError) {
    return <NoDataView hasQueueId={!!queueId} />;
  }

  switch (status) {
    case WORKFLOW_STATUS.ANNOTATING:
      return (
        <AnnotationView
          header={
            <AnnotatingHeader
              name={annotationQueue.name}
              processedCount={processedCount}
              totaCount={totalCount}
              content={
                <Button
                  variant="outline"
                  onClick={() => setStatus(WORKFLOW_STATUS.INITIAL)}
                >
                  <Info className="mr-1.5 size-3.5 shrink-0" />
                  Read instructions
                </Button>
              }
            />
          }
          currentItem={currentItem}
        />
      );
    case WORKFLOW_STATUS.COMPLETED:
      return (
        <CompletionView
          header={
            <AnnotatingHeader
              name={annotationQueue.name}
              processedCount={processedCount}
              totaCount={totalCount}
            />
          }
          queueItems={queueItems}
          processedCount={processedCount}
        />
      );
    case WORKFLOW_STATUS.INITIAL:
    default:
      return (
        <GetStartedView
          annotationQueue={annotationQueue}
          canStartAnnotation={canStartAnnotation}
          onStartAnnotating={() => {
            setStatus(WORKFLOW_STATUS.ANNOTATING);
            if (unprocessedItems.length > 0) {
              setCurrentIndex(
                queueItems.map((i) => i.id).indexOf(unprocessedItems[0].id),
              );
            }
          }}
        />
      );
  }
};

export default SMEFlowPage;
