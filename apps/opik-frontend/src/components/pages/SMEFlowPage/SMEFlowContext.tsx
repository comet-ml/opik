import React, {
  createContext,
  useContext,
  useState,
  useMemo,
  useEffect,
  useCallback,
} from "react";
import cloneDeep from "lodash/cloneDeep";
import omit from "lodash/omit";
import { StringParam, useQueryParam } from "use-query-params";
import { useSearch } from "@tanstack/react-router";
import { keepPreviousData } from "@tanstack/react-query";
import useAnnotationQueueById from "@/api/annotation-queues/useAnnotationQueueById";
import useTracesList from "@/api/traces/useTracesList";
import useThreadsList from "@/api/traces/useThreadsList";
import { generateAnnotationQueueIdFilter } from "@/lib/filters";
import {
  AnnotationQueue,
  ANNOTATION_QUEUE_SCOPE,
} from "@/types/annotation-queues";
import {
  Trace,
  Thread,
  TraceFeedbackScore,
  FEEDBACK_SCORE_TYPE,
} from "@/types/traces";
import { useLoggedInUserNameOrOpenSourceDefaultUser } from "@/store/AppStore";
import useCreateTraceCommentMutation from "@/api/traces/useCreateTraceCommentMutation";
import useCreateThreadCommentMutation from "@/api/traces/useCreateThreadCommentMutation";
import useUpdateTraceCommentMutation from "@/api/traces/useUpdateTraceCommentMutation";
import useUpdateThreadCommentMutation from "@/api/traces/useUpdateThreadCommentMutation";
import useTraceCommentsBatchDeleteMutation from "@/api/traces/useTraceCommentsBatchDeleteMutation";
import useThreadCommentsBatchDeleteMutation from "@/api/traces/useThreadCommentsBatchDeleteMutation";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import useThreadFeedbackScoreSetMutation from "@/api/traces/useThreadFeedbackScoreSetMutation";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";
import useThreadFeedbackScoreDeleteMutation from "@/api/traces/useThreadFeedbackScoreDeleteMutation";
import { UpdateFeedbackScoreData } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAnnotateViewer/types";
import {
  getAnnotationQueueItemId,
  getFeedbackScoresByUser,
  getLastCommentByUser,
} from "@/lib/annotation-queues";
import { findValueByAuthor, hasValuesByAuthor } from "@/lib/feedback-scores";

const isItemProcessed = (
  item: Trace | Thread,
  feedbackScoreNames: string[],
  userName: string | undefined,
): boolean => {
  if (!userName) return false;

  // Check if user has added feedback scores (if feedback definitions exist)
  const hasFeedbackScores = (item.feedback_scores || []).some((score) => {
    if (!feedbackScoreNames.includes(score.name)) return false;

    return hasValuesByAuthor(score)
      ? Boolean(findValueByAuthor(score.value_by_author, userName))
      : score.last_updated_by === userName;
  });

  // Check if user has added a comment
  const hasComment = (item.comments || []).some(
    (comment) => comment.created_by === userName,
  );

  // Item is processed if user has added feedback scores OR comments
  return hasFeedbackScores || hasComment;
};

export interface ValidationError {
  type: string;
  message: string;
  icon?: string;
}

export interface ValidationState {
  canSubmit: boolean;
  errors: ValidationError[];
}

const hasUnsavedChanges = (
  currentAnnotationState: AnnotationState,
): boolean => {
  const hasCommentChanges =
    (currentAnnotationState.comment?.text?.trim() || "") !==
    (currentAnnotationState.originalComment?.text?.trim() || "");

  const hasFeedbackScoreChanges =
    currentAnnotationState.scores.length !==
      currentAnnotationState.originalScores.length ||
    currentAnnotationState.scores.some((score) => {
      const originalScore = currentAnnotationState.originalScores.find(
        (original) => original.name === score.name,
      );

      return (
        !originalScore ||
        originalScore.value !== score.value ||
        originalScore.reason !== score.reason ||
        originalScore.category_name !== score.category_name
      );
    });

  return hasCommentChanges || hasFeedbackScoreChanges;
};

// Comment operation helpers
type CommentOperation =
  | { type: "none" }
  | { type: "create"; text: string }
  | { type: "update"; commentId: string; text: string }
  | { type: "delete"; commentId: string };

const getCommentOperation = (state: AnnotationState): CommentOperation => {
  const currentText = state.comment?.text?.trim();
  const originalId = state.originalComment?.id;
  const originalText = state.originalComment?.text;

  if (!currentText && !originalId) {
    return { type: "none" };
  }

  if (!currentText && originalId) {
    return {
      type: "delete",
      commentId: originalId,
    };
  }

  if (currentText && !originalId) {
    return {
      type: "create",
      text: currentText,
    };
  }

  if (currentText && originalId && originalText) {
    const textChanged = currentText !== originalText;
    return textChanged
      ? {
          type: "update",
          commentId: originalId,
          text: currentText,
        }
      : { type: "none" };
  }

  return { type: "none" };
};

const getDeletedScores = (state: AnnotationState) => {
  return state.originalScores.filter(
    (originalScore) =>
      !state.scores.some(
        (currentScore) => currentScore.name === originalScore.name,
      ),
  );
};

const getChangedScores = (state: AnnotationState) => {
  return state.scores.filter((score) => {
    const originalScore = state.originalScores.find(
      (original) => original.name === score.name,
    );

    return (
      !originalScore ||
      originalScore.value !== score.value ||
      originalScore.reason !== score.reason ||
      originalScore.category_name !== score.category_name
    );
  });
};

const validateCurrentItem = (
  item: Trace | Thread | undefined,
): ValidationError[] => {
  const errors: ValidationError[] = [];

  if (!item) return errors;

  return errors;
};

export type AnnotationState = {
  comment?: {
    text?: string;
    id?: string;
  };
  scores: TraceFeedbackScore[];
  originalComment?: {
    text?: string;
    id?: string;
  };
  originalScores: TraceFeedbackScore[];
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
  nextItem: Trace | Thread | undefined;
  currentView: WORKFLOW_STATUS;
  isCurrentItemProcessed: boolean;

  // Computed state
  processedCount: number;
  totalCount: number;
  canStartAnnotation: boolean;
  unprocessedItems: (Trace | Thread)[];
  hasAnyUnsavedChanges: boolean;

  // Annotation state
  currentAnnotationState: AnnotationState;

  // Validation state
  validationState: ValidationState;

  // Actions
  setCurrentView: (currentView: WORKFLOW_STATUS) => void;
  handleStartAnnotating: () => void;
  handleReviewAnnotations: () => void;
  handleNext: () => void;
  handlePrevious: () => void;
  handleSubmit: () => void;
  setCurrentIndex: (index: number) => void;
  setCurrentAnnotationState: (state: AnnotationState) => void;
  updateComment: (text: string) => void;
  updateFeedbackScore: (update: UpdateFeedbackScoreData) => void;
  deleteFeedbackScore: (name: string) => void;

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

  const currentUserName = useLoggedInUserNameOrOpenSourceDefaultUser();

  const { mutate: createTraceComment } = useCreateTraceCommentMutation();
  const { mutate: createThreadComment } = useCreateThreadCommentMutation();
  const { mutate: updateTraceComment } = useUpdateTraceCommentMutation();
  const { mutate: updateThreadComment } = useUpdateThreadCommentMutation();
  const { mutate: deleteTraceComments } = useTraceCommentsBatchDeleteMutation();
  const { mutate: deleteThreadComments } =
    useThreadCommentsBatchDeleteMutation();
  const { mutate: setTraceFeedbackScore } = useTraceFeedbackScoreSetMutation();
  const { mutate: setThreadFeedbackScore } =
    useThreadFeedbackScoreSetMutation();
  const { mutate: deleteTraceFeedbackScore } =
    useTraceFeedbackScoreDeleteMutation();
  const { mutate: deleteThreadFeedbackScore } =
    useThreadFeedbackScoreDeleteMutation();

  const [currentIndex, setCurrentIndex] = useState(0);
  const [currentAnnotationState, setCurrentAnnotationState] =
    useState<AnnotationState>({
      scores: [],
      originalScores: [],
    });

  const [cachedAnnotationStates, setCachedAnnotationStates] = useState<
    Record<string, AnnotationState>
  >({});

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

  const annotationQueueFilter = useMemo(
    () => generateAnnotationQueueIdFilter(annotationQueue?.id),
    [annotationQueue?.id],
  );

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

  const {
    unprocessedItems,
    processedCount,
    totalCount,
    canStartAnnotation,
    unprocessedIds,
    allItemIds,
  } = useMemo(() => {
    const feedbackScoreNames = annotationQueue?.feedback_definition_names ?? [];
    const unprocessedItems = queueItems.filter(
      (item) => !isItemProcessed(item, feedbackScoreNames, currentUserName),
    );
    const totalCount = queueItems.length;
    const processedCount = totalCount - unprocessedItems.length;

    const unprocessedIds = unprocessedItems.map((item) => item.id);
    const allItemIds = queueItems.map((item) => item.id);

    return {
      unprocessedItems,
      processedCount,
      totalCount,
      canStartAnnotation: unprocessedItems.length > 0,
      unprocessedIds,
      allItemIds,
    };
  }, [annotationQueue?.feedback_definition_names, queueItems, currentUserName]);

  const currentItem = useMemo(() => {
    return queueItems[currentIndex] || undefined;
  }, [queueItems, currentIndex]);

  const nextItem = useMemo(() => {
    return queueItems[currentIndex + 1] || undefined;
  }, [queueItems, currentIndex]);

  const isItemsLoading = isTracesLoading || isThreadsLoading;

  const handleStartAnnotating = useCallback(() => {
    setCurrentView(WORKFLOW_STATUS.ANNOTATING);
    if (unprocessedIds.length > 0) {
      setCurrentIndex(allItemIds.indexOf(unprocessedIds[0]));
    }
  }, [setCurrentView, unprocessedIds, allItemIds]);

  const handleReviewAnnotations = useCallback(() => {
    setCurrentView(WORKFLOW_STATUS.ANNOTATING);
    setCurrentIndex(0);
  }, [setCurrentView]);

  const handleNext = useCallback(() => {
    // Always cache the current annotation state when navigating away
    // This ensures that any changes (including deletions) are preserved
    if (currentItem) {
      setCachedAnnotationStates((prev) => ({
        ...prev,
        [getAnnotationQueueItemId(currentItem)]: cloneDeep(
          currentAnnotationState,
        ),
      }));
    }

    if (currentIndex < queueItems.length - 1) {
      setCurrentIndex(currentIndex + 1);
    }
  }, [currentIndex, queueItems.length, currentItem, currentAnnotationState]);

  const getNextUnprocessedIndex = useCallback(
    (currentIndex: number) => {
      if (unprocessedIds.length === 0) {
        return Math.min(currentIndex + 1, queueItems.length - 1);
      }

      const currentId = queueItems[currentIndex]?.id;
      const currentUnprocessedIdx = unprocessedIds.indexOf(currentId);

      if (
        currentUnprocessedIdx === -1 ||
        currentUnprocessedIdx === unprocessedIds.length - 1
      ) {
        const firstUnprocessedId = unprocessedIds[0];
        return allItemIds.indexOf(firstUnprocessedId);
      }

      const nextUnprocessedId = unprocessedIds[currentUnprocessedIdx + 1];
      return allItemIds.indexOf(nextUnprocessedId);
    },
    [queueItems, unprocessedIds, allItemIds],
  );

  const isCurrentItemProcessed = useMemo(() => {
    if (!currentItem || !annotationQueue) return false;
    const feedbackScoreNames = annotationQueue.feedback_definition_names ?? [];
    return isItemProcessed(currentItem, feedbackScoreNames, currentUserName);
  }, [currentItem, annotationQueue, currentUserName]);

  const handleNextUnprocessed = useCallback(() => {
    const nextIndex = getNextUnprocessedIndex(currentIndex);
    if (nextIndex !== -1) {
      setCurrentIndex(nextIndex);
    }
  }, [currentIndex, getNextUnprocessedIndex]);

  const handlePrevious = useCallback(() => {
    // Always cache the current annotation state when navigating away
    // This ensures that any changes (including deletions) are preserved
    if (currentItem) {
      setCachedAnnotationStates((prev) => ({
        ...prev,
        [getAnnotationQueueItemId(currentItem)]: cloneDeep(
          currentAnnotationState,
        ),
      }));
    }

    if (currentIndex > 0) {
      setCurrentIndex(currentIndex - 1);
    }
  }, [currentIndex, currentItem, currentAnnotationState]);

  const updateComment = useCallback((text: string) => {
    setCurrentAnnotationState((prev) => ({
      ...prev,
      comment: {
        id: prev.comment?.id || prev.originalComment?.id,
        text,
      },
    }));
  }, []);

  const updateFeedbackScore = useCallback(
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

  const deleteFeedbackScore = useCallback((name: string) => {
    setCurrentAnnotationState((prev) => ({
      ...prev,
      scores: prev.scores.filter((score) => score.name !== name),
    }));
  }, []);

  const isThread = annotationQueue?.scope === ANNOTATION_QUEUE_SCOPE.THREAD;

  const submitTraceComment = useCallback(
    (state: AnnotationState, trace: Trace) => {
      const operation = getCommentOperation(state);

      switch (operation.type) {
        case "create":
          createTraceComment({
            traceId: trace.id,
            text: operation.text,
          });
          break;

        case "update":
          updateTraceComment({
            commentId: operation.commentId,
            traceId: trace.id,
            text: operation.text,
          });
          break;

        case "delete":
          deleteTraceComments({
            ids: [operation.commentId],
            traceId: trace.id,
          });
          break;

        case "none":
          break;
      }
    },
    [createTraceComment, updateTraceComment, deleteTraceComments],
  );

  const submitThreadComment = useCallback(
    (state: AnnotationState, thread: Thread) => {
      const operation = getCommentOperation(state);

      switch (operation.type) {
        case "create":
          createThreadComment({
            threadId: getAnnotationQueueItemId(thread),
            projectId: thread.project_id,
            text: operation.text,
          });
          break;

        case "update":
          updateThreadComment({
            commentId: operation.commentId,
            projectId: thread.project_id,
            text: operation.text,
          });
          break;

        case "delete":
          deleteThreadComments({
            ids: [operation.commentId],
            threadId: getAnnotationQueueItemId(thread),
            projectId: thread.project_id,
          });
          break;

        case "none":
          break;
      }
    },
    [createThreadComment, updateThreadComment, deleteThreadComments],
  );

  const submitTraceFeedbackScores = useCallback(
    (state: AnnotationState, trace: Trace) => {
      const deletedScores = getDeletedScores(state);
      const changedScores = getChangedScores(state);

      deletedScores.forEach((score) => {
        deleteTraceFeedbackScore({
          traceId: trace.id,
          name: score.name,
        });
      });

      changedScores.forEach((score) => {
        setTraceFeedbackScore({
          traceId: trace.id,
          name: score.name,
          categoryName: score.category_name,
          value: score.value,
          reason: score.reason,
        });
      });
    },
    [setTraceFeedbackScore, deleteTraceFeedbackScore],
  );

  const submitThreadFeedbackScores = useCallback(
    (state: AnnotationState, thread: Thread) => {
      if (!annotationQueue) return;

      const deletedScores = getDeletedScores(state);
      const changedScores = getChangedScores(state);

      if (deletedScores.length) {
        deleteThreadFeedbackScore({
          threadId: thread.id,
          projectId: thread.project_id,
          projectName: annotationQueue.project_name,
          names: deletedScores.map((s) => s.name),
        });
      }

      if (changedScores.length) {
        setThreadFeedbackScore({
          threadId: thread.id,
          projectId: thread.project_id,
          projectName: annotationQueue.project_name,
          scores: changedScores.map((score) => ({
            name: score.name,
            categoryName: score.category_name,
            value: score.value,
            reason: score.reason,
          })),
        });
      }
    },
    [annotationQueue, setThreadFeedbackScore, deleteThreadFeedbackScore],
  );

  const handleSubmit = useCallback(() => {
    if (!currentItem || !annotationQueue) return;

    if (isThread) {
      submitThreadComment(currentAnnotationState, currentItem as Thread);
      submitThreadFeedbackScores(currentAnnotationState, currentItem as Thread);
    } else {
      submitTraceComment(currentAnnotationState, currentItem as Trace);
      submitTraceFeedbackScores(currentAnnotationState, currentItem as Trace);
    }

    setCachedAnnotationStates((prev) =>
      omit(prev, getAnnotationQueueItemId(currentItem)),
    );

    const wasLastUnprocessed =
      unprocessedIds.length === 1 && unprocessedIds[0] === currentItem.id;

    if (wasLastUnprocessed) {
      setCurrentView(WORKFLOW_STATUS.COMPLETED);
    } else {
      handleNextUnprocessed();
    }
  }, [
    currentItem,
    annotationQueue,
    isThread,
    currentAnnotationState,
    submitTraceComment,
    submitThreadComment,
    submitTraceFeedbackScores,
    submitThreadFeedbackScores,
    handleNextUnprocessed,
    unprocessedIds,
    setCurrentView,
  ]);

  useEffect(() => {
    if (!currentItem) {
      setCurrentAnnotationState({
        scores: [],
        originalScores: [],
      });
      return;
    }

    const cachedState =
      cachedAnnotationStates[getAnnotationQueueItemId(currentItem)];
    if (cachedState) {
      setCurrentAnnotationState(cachedState);
      return;
    }

    const lastComment = getLastCommentByUser(
      currentItem.comments,
      currentUserName,
    );

    const userFeedbackScores = getFeedbackScoresByUser(
      currentItem.feedback_scores || [],
      currentUserName,
      annotationQueue?.feedback_definition_names ?? [],
    );

    setCurrentAnnotationState({
      comment: lastComment
        ? {
            text: lastComment.text,
            id: lastComment.id,
          }
        : undefined,
      scores: userFeedbackScores,
      originalComment: lastComment ? cloneDeep(lastComment) : undefined,
      originalScores: cloneDeep(userFeedbackScores),
    });
  }, [
    currentItem,
    currentUserName,
    annotationQueue?.feedback_definition_names,
    cachedAnnotationStates,
  ]);

  const validationState = useMemo((): ValidationState => {
    const errors = validateCurrentItem(currentItem);
    const hasChanges = hasUnsavedChanges(currentAnnotationState);
    return {
      canSubmit: errors.length === 0 && hasChanges,
      errors,
    };
  }, [currentItem, currentAnnotationState]);

  useEffect(() => {
    if (!currentView && !isItemsLoading && queueItems.length > 0) {
      if (unprocessedItems.length === 0) {
        setCurrentView(WORKFLOW_STATUS.COMPLETED);
      } else {
        setCurrentView(WORKFLOW_STATUS.INITIAL);
      }
    }
  }, [
    isItemsLoading,
    queueItems.length,
    unprocessedItems.length,
    currentView,
    setCurrentView,
  ]);

  const hasAnyUnsavedChanges = useMemo(() => {
    const currentItemHasChanges = hasUnsavedChanges(currentAnnotationState);
    const cachedItemsHaveChanges = Object.values(cachedAnnotationStates).some(
      (state) => hasUnsavedChanges(state),
    );
    return currentItemHasChanges || cachedItemsHaveChanges;
  }, [currentAnnotationState, cachedAnnotationStates]);

  const contextValue: SMEFlowContextValue = {
    // Queue data
    annotationQueue,
    queueItems,

    // Navigation state
    currentIndex,
    currentItem,
    nextItem,
    currentView: currentView || WORKFLOW_STATUS.INITIAL,
    isCurrentItemProcessed,

    // Computed state
    processedCount,
    totalCount,
    canStartAnnotation,
    unprocessedItems,
    hasAnyUnsavedChanges,

    // Annotation state
    currentAnnotationState,

    // Validation state
    validationState,

    // Actions
    setCurrentView,
    handleStartAnnotating,
    handleReviewAnnotations,
    handleNext,
    handlePrevious,
    handleSubmit,
    setCurrentIndex,
    setCurrentAnnotationState,
    updateComment,
    updateFeedbackScore,
    deleteFeedbackScore,

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
