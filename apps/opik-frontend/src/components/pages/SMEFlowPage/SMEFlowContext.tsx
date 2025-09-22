import React, {
  createContext,
  useContext,
  useState,
  useMemo,
  useEffect,
  useCallback,
} from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { useSearch } from "@tanstack/react-router";
import { keepPreviousData } from "@tanstack/react-query";
import useAnnotationQueueById from "@/api/annotation-queues/useAnnotationQueueById";
import useTracesList from "@/api/traces/useTracesList";
import useThreadsList from "@/api/traces/useThreadsList";
import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";
import { createFilter } from "@/lib/filters";
import { AnnotationQueue } from "@/types/annotation-queues";
import {
  Trace,
  Thread,
  TraceFeedbackScore,
  FEEDBACK_SCORE_TYPE,
} from "@/types/traces";
import { CommentItem } from "@/types/comment";
import { useLoggedInUserName } from "@/store/AppStore";
import useCreateTraceCommentMutation from "@/api/traces/useCreateTraceCommentMutation";
import useCreateThreadCommentMutation from "@/api/traces/useCreateThreadCommentMutation";
import useUpdateTraceCommentMutation from "@/api/traces/useUpdateTraceCommentMutation";
import useUpdateThreadCommentMutation from "@/api/traces/useUpdateThreadCommentMutation";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import useThreadFeedbackScoreSetMutation from "@/api/traces/useThreadFeedbackScoreSetMutation";
import { UpdateFeedbackScoreData } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAnnotateViewer/types";

const isItemProcessed = (
  item: Trace | Thread,
  feedbackScoreNames: string[],
): boolean => {
  return (item.feedback_scores || []).some((score) =>
    feedbackScoreNames.includes(score.name),
  );
};

const getLastCommentByUser = (
  comments: CommentItem[] | undefined,
  userName: string | undefined,
): CommentItem | undefined => {
  if (!comments || !userName) return undefined;

  const userComments = comments.filter(
    (comment) => comment.created_by === userName,
  );
  if (userComments.length === 0) return undefined;

  return userComments.reduce((latest, current) =>
    new Date(current.created_at) > new Date(latest.created_at)
      ? current
      : latest,
  );
};

export type AnnotationState = {
  comment?: CommentItem;
  scores: TraceFeedbackScore[];
};

export enum WORKFLOW_STATUS {
  INITIAL = "initial",
  ANNOTATING = "annotating",
  COMPLETED = "completed",
}

const MAX_QUEUE_ITEMS = 15000;

interface SMEFlowContextValue {
  // Queue data
  annotationQueue: AnnotationQueue | undefined;
  queueItems: (Trace | Thread)[];

  // Navigation state
  currentIndex: number;
  currentItem: Trace | Thread | undefined;
  currentView: WORKFLOW_STATUS;

  // Computed state
  processedCount: number;
  totalCount: number;
  canStartAnnotation: boolean;
  unprocessedItems: (Trace | Thread)[];

  // Annotation state
  currentAnnotationState: AnnotationState;

  // Actions
  setCurrentView: (currentView: WORKFLOW_STATUS) => void;
  handleStartAnnotating: () => void;
  handleNext: () => void;
  handlePrevious: () => void;
  handleSubmit: () => Promise<void>;
  setCurrentIndex: (index: number) => void;
  setCurrentAnnotationState: (state: AnnotationState) => void;
  updateLocalComment: (text: string) => void;
  updateLocalFeedbackScore: (update: UpdateFeedbackScoreData) => void;
  deleteLocalFeedbackScore: (name: string) => void;

  // Loading states
  isLoading: boolean;
  isError: boolean;
  isItemsLoading: boolean;
}

const SMEFlowContext = createContext<SMEFlowContextValue | null>(null);

export const useSMEFlow = () => {
  const context = useContext(SMEFlowContext);
  if (!context) {
    throw new Error("useSMEFlow must be used within SMEFlowProvider");
  }
  return context;
};

interface SMEFlowProviderProps {
  children: React.ReactNode;
}

export const SMEFlowProvider: React.FunctionComponent<SMEFlowProviderProps> = ({
  children,
}) => {
  const [currentView, setCurrentView] = useQueryParam("status", StringParam, {
    updateType: "replaceIn",
  }) as [WORKFLOW_STATUS, (currentView: WORKFLOW_STATUS) => void];

  const search = useSearch({ strict: false }) as { queueId?: string };
  const { queueId } = search;
  const currentUserName = useLoggedInUserName();

  const { mutate: createTraceComment } = useCreateTraceCommentMutation();
  const { mutate: createThreadComment } = useCreateThreadCommentMutation();
  const { mutate: updateTraceComment } = useUpdateTraceCommentMutation();
  const { mutate: updateThreadComment } = useUpdateThreadCommentMutation();
  const { mutate: setTraceFeedbackScore } = useTraceFeedbackScoreSetMutation();
  const { mutate: setThreadFeedbackScore } =
    useThreadFeedbackScoreSetMutation();

  const [currentIndex, setCurrentIndex] = useState(0);
  const [currentAnnotationState, setCurrentAnnotationState] =
    useState<AnnotationState>({
      scores: [],
    });

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
    return [
      createFilter({
        field: "annotation_queue_ids",
        value: annotationQueue.id,
        operator: "contains",
      }),
    ];
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

  const currentItem = useMemo(() => {
    return queueItems[currentIndex] || undefined;
  }, [queueItems, currentIndex]);

  const isItemsLoading = isTracesLoading || isThreadsLoading;

  const handleStartAnnotating = useCallback(() => {
    setCurrentView(WORKFLOW_STATUS.ANNOTATING);
    if (unprocessedItems.length > 0) {
      setCurrentIndex(
        queueItems.map((i) => i.id).indexOf(unprocessedItems[0].id),
      );
    }
  }, [setCurrentView, unprocessedItems, queueItems]);

  const handleNext = useCallback(() => {
    if (currentIndex < queueItems.length - 1) {
      setCurrentIndex(currentIndex + 1);
    }
  }, [currentIndex, queueItems.length]);

  const handlePrevious = useCallback(() => {
    if (currentIndex > 0) {
      setCurrentIndex(currentIndex - 1);
    }
  }, [currentIndex]);

  const updateLocalComment = useCallback(
    (text: string) => {
      setCurrentAnnotationState((prev) => ({
        ...prev,
        comment: {
          id: prev.comment?.id || `temp-${Date.now()}`,
          text,
          created_by: currentUserName || "",
          created_at: new Date().toISOString(),
          last_updated_at: new Date().toISOString(),
          last_updated_by: currentUserName || "",
        },
      }));
    },
    [currentUserName],
  );

  // TODO lala check
  const updateLocalFeedbackScore = useCallback(
    (update: UpdateFeedbackScoreData) => {
      setCurrentAnnotationState((prev) => {
        const existingScoreIndex = prev.scores.findIndex(
          (score) => score.name === update.name,
        );

        const newScore: TraceFeedbackScore = {
          name: update.name,
          category_name: update.categoryName,
          value: update.value,
          reason: update.reason,
          source: FEEDBACK_SCORE_TYPE.ui,
          created_by: currentUserName || "",
          last_updated_by: currentUserName || "",
          last_updated_at: new Date().toISOString(),
        };

        if (existingScoreIndex >= 0) {
          const updatedScores = [...prev.scores];
          updatedScores[existingScoreIndex] = newScore;
          return { ...prev, scores: updatedScores };
        } else {
          return { ...prev, scores: [...prev.scores, newScore] };
        }
      });
    },
    [currentUserName],
  );

  // TODO lala check
  const deleteLocalFeedbackScore = useCallback((name: string) => {
    setCurrentAnnotationState((prev) => ({
      ...prev,
      scores: prev.scores.filter((score) => score.name !== name),
    }));
  }, []);

  // TODO lala rework this
  // Helper function to determine if comment is new or existing
  const isNewComment = useCallback(
    (comment: CommentItem | undefined): boolean => {
      if (!comment?.text?.trim()) return false;

      // If comment has an id that doesn't start with "temp-", it's existing
      if (comment.id && !comment.id.startsWith("temp-")) {
        return false;
      }

      // Check if this comment text already exists in the current item's comments
      if (currentItem?.comments) {
        return !currentItem.comments.some(
          (existingComment) =>
            existingComment.created_by === currentUserName &&
            existingComment.text === comment.text,
        );
      }

      return true;
    },
    [currentItem?.comments, currentUserName],
  );

  // Helper function to submit comment
  const submitComment = useCallback(
    (comment: CommentItem, item: Trace | Thread) => {
      if (!comment.text?.trim()) return Promise.resolve();

      const isNew = isNewComment(comment);

      return new Promise<void>((resolve, reject) => {
        if ("project_name" in item) {
          // It's a Trace
          if (isNew) {
            createTraceComment(
              {
                traceId: item.id,
                text: comment.text,
              },
              {
                onSuccess: () => resolve(),
                onError: (error) => reject(error),
              },
            );
          } else {
            updateTraceComment(
              {
                commentId: comment.id,
                traceId: item.id,
                text: comment.text,
              },
              {
                onSuccess: () => resolve(),
                onError: (error) => reject(error),
              },
            );
          }
        } else {
          // It's a Thread
          if (isNew) {
            createThreadComment(
              {
                threadId: item.id,
                projectId: item.project_id,
                text: comment.text,
              },
              {
                onSuccess: () => resolve(),
                onError: (error) => reject(error),
              },
            );
          } else {
            updateThreadComment(
              {
                commentId: comment.id,
                projectId: item.project_id,
                text: comment.text,
              },
              {
                onSuccess: () => resolve(),
                onError: (error) => reject(error),
              },
            );
          }
        }
      });
    },
    [
      isNewComment,
      createTraceComment,
      updateTraceComment,
      createThreadComment,
      updateThreadComment,
    ],
  );

  // Helper function to submit feedback scores
  const submitFeedbackScores = useCallback(
    (scores: TraceFeedbackScore[], item: Trace | Thread) => {
      if (scores.length === 0) return Promise.resolve();

      const promises = scores.map((score) => {
        return new Promise<void>((resolve, reject) => {
          if ("project_name" in item) {
            // It's a Trace
            setTraceFeedbackScore(
              {
                traceId: item.id,
                name: score.name,
                categoryName: score.category_name,
                value: score.value,
                reason: score.reason,
              },
              {
                onSuccess: () => resolve(),
                onError: (error) => reject(error),
              },
            );
          } else {
            // It's a Thread
            setThreadFeedbackScore(
              {
                threadId: item.id,
                projectId: item.project_id,
                projectName: annotationQueue?.project_name || "",
                name: score.name,
                categoryName: score.category_name,
                value: score.value,
                reason: score.reason,
              },
              {
                onSuccess: () => resolve(),
                onError: (error) => reject(error),
              },
            );
          }
        });
      });

      return Promise.all(promises);
    },
    [
      setTraceFeedbackScore,
      setThreadFeedbackScore,
      annotationQueue?.project_name,
    ],
  );

  // Main submit handler
  const handleSubmit = useCallback(async () => {
    if (!currentItem || !annotationQueue) return;

    try {
      // Submit comment if exists
      if (currentAnnotationState.comment) {
        await submitComment(currentAnnotationState.comment, currentItem);
      }

      // Submit feedback scores
      if (currentAnnotationState.scores.length > 0) {
        await submitFeedbackScores(currentAnnotationState.scores, currentItem);
      }

      // Move to next item or complete
      handleNext();
    } catch (error) {
      console.error("Failed to submit annotation:", error);
      // Error handling is already done by the mutation hooks (toast notifications)
    }
  }, [
    currentItem,
    annotationQueue,
    currentAnnotationState,
    submitComment,
    submitFeedbackScores,
    handleNext,
  ]);

  useEffect(() => {
    if (!currentItem) {
      setCurrentAnnotationState({ scores: [] });
      return;
    }

    const lastComment = getLastCommentByUser(
      currentItem.comments,
      currentUserName,
    );

    const feedbackScores = currentItem.feedback_scores || [];

    setCurrentAnnotationState({
      comment: lastComment,
      scores: feedbackScores,
    });
  }, [currentItem, currentUserName]);

  const contextValue: SMEFlowContextValue = {
    // Queue data
    annotationQueue,
    queueItems,

    // Navigation state
    currentIndex,
    currentItem,
    currentView: currentView || WORKFLOW_STATUS.INITIAL,

    // Computed state
    processedCount,
    totalCount,
    canStartAnnotation,
    unprocessedItems,

    // Annotation state
    currentAnnotationState,

    // Actions
    setCurrentView,
    handleStartAnnotating,
    handleNext,
    handlePrevious,
    handleSubmit,
    setCurrentIndex,
    setCurrentAnnotationState,
    updateLocalComment,
    updateLocalFeedbackScore,
    deleteLocalFeedbackScore,

    // Loading states
    isLoading,
    isError,
    isItemsLoading,
  };

  return (
    <SMEFlowContext.Provider value={contextValue}>
      {children}
    </SMEFlowContext.Provider>
  );
};

export default SMEFlowProvider;
