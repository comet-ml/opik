import React, {
  createContext,
  useContext,
  useState,
  useMemo,
  useEffect,
  useCallback,
  useRef,
} from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { useSearch } from "@tanstack/react-router";
import { keepPreviousData } from "@tanstack/react-query";
import useAnnotationQueueById from "@/api/annotation-queues/useAnnotationQueueById";
import useAnnotationQueueLocks from "@/api/annotation-queues/useAnnotationQueueLocks";
import { useAnnotationQueueItemLockMutation } from "@/api/annotation-queues/useAnnotationQueueItemLockMutation";
import useTracesList from "@/api/traces/useTracesList";
import useThreadsList from "@/api/traces/useThreadsList";
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
import { UpdateFeedbackScoreData } from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceAnnotateViewer/types";
import {
  DEFAULT_LOCK_TIMEOUT_SECONDS,
  getAnnotationQueueItemId,
  getFeedbackScoresByUser,
  getLastCommentByUser,
  getItemState,
  hashCode,
  ITEM_STATE,
  AnnotationState,
} from "@/lib/annotation-queues";
import { useAnnotationPersistence } from "./useAnnotationPersistence";

export { ITEM_STATE } from "@/lib/annotation-queues";

const POLLING_INTERVAL_MS = 30000;
const LOCKS_POLLING_INTERVAL_MS = 5000;

export type { AnnotationState } from "@/lib/annotation-queues";

export enum WORKFLOW_STATUS {
  INITIAL = "initial",
  ANNOTATING = "annotating",
  COMPLETED = "completed",
}

const MAX_QUEUE_ITEMS = 15000;

interface SMEFlowContextValue {
  annotationQueue: AnnotationQueue | undefined;
  queueItems: (Trace | Thread)[];

  currentIndex: number;
  currentItem: Trace | Thread | undefined;
  nextDefaultItem: Trace | Thread | undefined;
  currentView: WORKFLOW_STATUS;
  currentItemLockDenied: boolean;

  processedCount: number;
  totalCount: number;
  canStartAnnotation: boolean;
  itemStates: Record<string, ITEM_STATE>;
  shuffledItemIds: string[];

  currentAnnotationState: AnnotationState;

  setCurrentView: (currentView: WORKFLOW_STATUS) => void;
  handleStartAnnotating: () => void;
  handleReviewAnnotations: () => void;
  handleNextDefault: () => void;
  navigateToItem: (index: number) => void;
  flushPendingChanges: () => void;
  updateComment: (text: string) => void;
  updateFeedbackScore: (update: UpdateFeedbackScoreData) => void;
  deleteFeedbackScore: (name: string) => void;

  isLoading: boolean;
  isError: boolean;
  isItemsLoading: boolean;
  isItemsError: boolean;
}

const SMEFlowContext = createContext<SMEFlowContextValue | null>(null);

export const useSMEFlow = () => {
  const context = useContext(SMEFlowContext);
  if (!context) {
    throw new Error("useSMEFlow must be used within SMEFlowProvider");
  }
  return context;
};

export const SMEFlowProvider: React.FunctionComponent<{
  children: React.ReactNode;
}> = ({ children }) => {
  const [currentView, setCurrentView] = useQueryParam("status", StringParam, {
    updateType: "replaceIn",
  }) as [WORKFLOW_STATUS, (currentView: WORKFLOW_STATUS) => void];

  const search = useSearch({ strict: false }) as { queueId?: string };
  const { queueId } = search;
  const currentUserName = useLoggedInUserNameOrOpenSourceDefaultUser();

  const [currentIndex, setCurrentIndex] = useState(0);
  const [currentAnnotationState, setCurrentAnnotationState] =
    useState<AnnotationState>({ scores: [] });

  const activeItemIdRef = useRef<string | undefined>(undefined);
  const annotationStateRef = useRef(currentAnnotationState);
  annotationStateRef.current = currentAnnotationState;
  const currentItemRef = useRef<Trace | Thread | undefined>(undefined);

  // --- Data fetching ---

  const {
    data: annotationQueue,
    isLoading,
    isError,
  } = useAnnotationQueueById(
    { annotationQueueId: queueId! },
    { enabled: !!queueId },
  );

  const ascendingSort = [{ id: "created_at", desc: false }];

  const {
    data: tracesData,
    isLoading: isTracesLoading,
    isError: isTracesError,
  } = useTracesList(
    {
      projectId: annotationQueue?.project_id || "",
      page: 1,
      size: MAX_QUEUE_ITEMS,
      sorting: ascendingSort,
      search: "",
      truncate: true,
      stripAttachments: true,
      annotationQueueId: annotationQueue?.id,
    },
    {
      enabled:
        !!annotationQueue &&
        !!annotationQueue.project_id &&
        annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.TRACE,
      placeholderData: keepPreviousData,
      refetchInterval: POLLING_INTERVAL_MS,
    },
  );

  const {
    data: threadsData,
    isLoading: isThreadsLoading,
    isError: isThreadsError,
  } = useThreadsList(
    {
      projectId: annotationQueue?.project_id || "",
      page: 1,
      size: MAX_QUEUE_ITEMS,
      sorting: ascendingSort,
      search: "",
      truncate: true,
      annotationQueueId: annotationQueue?.id,
    },
    {
      enabled:
        !!annotationQueue &&
        !!annotationQueue.project_id &&
        annotationQueue.scope === ANNOTATION_QUEUE_SCOPE.THREAD,
      placeholderData: keepPreviousData,
      refetchInterval: POLLING_INTERVAL_MS,
    },
  );

  const { data: locksData } = useAnnotationQueueLocks(
    { queueId: queueId! },
    {
      enabled: !!queueId,
      refetchInterval: LOCKS_POLLING_INTERVAL_MS,
    },
  );

  const lockMutation = useAnnotationQueueItemLockMutation();
  const { mutate: lockMutate, reset: lockReset } = lockMutation;

  const locks = useMemo(() => locksData?.locks ?? {}, [locksData?.locks]);

  const isItemsError =
    annotationQueue?.scope === ANNOTATION_QUEUE_SCOPE.TRACE
      ? isTracesError
      : annotationQueue?.scope === ANNOTATION_QUEUE_SCOPE.THREAD
        ? isThreadsError
        : false;

  const isItemsLoading = isTracesLoading || isThreadsLoading;

  const queueItems = useMemo(() => {
    return (
      (annotationQueue?.scope === ANNOTATION_QUEUE_SCOPE.TRACE
        ? tracesData?.content
        : threadsData?.content) ?? []
    );
  }, [annotationQueue?.scope, tracesData?.content, threadsData?.content]);

  // --- Computed state ---

  const annotatorsPerItem = annotationQueue?.annotators_per_item ?? 1;

  const {
    itemStates,
    processedCount,
    totalCount,
    canStartAnnotation,
    defaultItemIds,
    allItemIds,
  } = useMemo(() => {
    const feedbackScoreNames = annotationQueue?.feedback_definition_names ?? [];
    const states: Record<string, ITEM_STATE> = {};
    const defaultIds: string[] = [];
    const allIds: string[] = [];
    let processed = 0;

    queueItems.forEach((item) => {
      const itemId = getAnnotationQueueItemId(item);
      const state = getItemState(
        item,
        feedbackScoreNames,
        currentUserName,
        annotatorsPerItem,
        locks[itemId],
      );

      states[itemId] = state;
      allIds.push(itemId);

      if (state === ITEM_STATE.DEFAULT) {
        defaultIds.push(itemId);
      } else {
        processed++;
      }
    });

    return {
      itemStates: states,
      processedCount: processed,
      totalCount: queueItems.length,
      canStartAnnotation: defaultIds.length > 0,
      defaultItemIds: defaultIds,
      allItemIds: allIds,
    };
  }, [
    queueItems,
    annotationQueue?.feedback_definition_names,
    currentUserName,
    annotatorsPerItem,
    locks,
  ]);

  const currentItem = useMemo(() => {
    return queueItems[currentIndex] || undefined;
  }, [queueItems, currentIndex]);
  currentItemRef.current = currentItem;

  // Per-user deterministic shuffle of items
  const shuffledItemIds = useMemo(() => {
    const prefix = (currentUserName ?? "") + (annotationQueue?.id ?? "");
    return [...allItemIds].sort(
      (a, b) => hashCode(prefix + a) - hashCode(prefix + b),
    );
  }, [allItemIds, currentUserName, annotationQueue?.id]);

  const nextDefaultItem = useMemo(() => {
    if (defaultItemIds.length === 0) return undefined;
    const currentItemId = currentItem
      ? getAnnotationQueueItemId(currentItem)
      : "";
    const currentIdx = shuffledItemIds.indexOf(currentItemId);
    for (let i = 1; i < shuffledItemIds.length; i++) {
      const candidateId =
        shuffledItemIds[(currentIdx + i) % shuffledItemIds.length];
      if (itemStates[candidateId] === ITEM_STATE.DEFAULT) {
        return queueItems.find(
          (item) => getAnnotationQueueItemId(item) === candidateId,
        );
      }
    }
    return undefined;
  }, [queueItems, currentItem, shuffledItemIds, defaultItemIds, itemStates]);

  // --- Persistence ---

  const { scheduleSave, flushPendingChanges, resetLastSaved } =
    useAnnotationPersistence({
      annotationQueue,
      annotationStateRef,
      currentItemRef,
    });

  // --- Navigation ---

  const handleStartAnnotating = useCallback(() => {
    setCurrentView(WORKFLOW_STATUS.ANNOTATING);
    const firstDefault = shuffledItemIds.find(
      (id) => itemStates[id] === ITEM_STATE.DEFAULT,
    );
    if (firstDefault) {
      setCurrentIndex(allItemIds.indexOf(firstDefault));
    }
  }, [setCurrentView, shuffledItemIds, allItemIds, itemStates]);

  const handleReviewAnnotations = useCallback(() => {
    setCurrentView(WORKFLOW_STATUS.ANNOTATING);
    setCurrentIndex(0);
  }, [setCurrentView]);

  const handleNextDefault = useCallback(() => {
    flushPendingChanges();

    if (!nextDefaultItem) {
      setCurrentView(WORKFLOW_STATUS.COMPLETED);
      return;
    }

    const nextIndex = allItemIds.indexOf(
      getAnnotationQueueItemId(nextDefaultItem),
    );
    if (nextIndex !== -1) {
      setCurrentIndex(nextIndex);
    } else {
      setCurrentView(WORKFLOW_STATUS.COMPLETED);
    }
  }, [flushPendingChanges, nextDefaultItem, allItemIds, setCurrentView]);

  const navigateToItem = useCallback(
    (index: number) => {
      flushPendingChanges();
      setCurrentIndex(index);
    },
    [flushPendingChanges],
  );

  // --- Annotation state updaters (trigger debounced save) ---

  const updateComment = useCallback(
    (text: string) => {
      setCurrentAnnotationState((prev) => ({ ...prev, comment: { text } }));
      scheduleSave();
    },
    [scheduleSave],
  );

  const updateFeedbackScore = useCallback(
    (update: UpdateFeedbackScoreData) => {
      setCurrentAnnotationState((prev) => {
        const existingIdx = prev.scores.findIndex(
          (s) => s.name === update.name,
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

        if (existingIdx >= 0) {
          const updatedScores = [...prev.scores];
          updatedScores[existingIdx] = newScore;
          return { ...prev, scores: updatedScores };
        }
        return { ...prev, scores: [...prev.scores, newScore] };
      });
      scheduleSave();
    },
    [currentUserName, scheduleSave],
  );

  const deleteFeedbackScore = useCallback(
    (name: string) => {
      setCurrentAnnotationState((prev) => ({
        ...prev,
        scores: prev.scores.filter((s) => s.name !== name),
      }));
      scheduleSave();
    },
    [scheduleSave],
  );

  // --- Lock lifecycle: lock on view, heartbeat to keep alive ---

  useEffect(() => {
    if (!currentItem || !queueId || !annotationQueue) return;

    const itemId = getAnnotationQueueItemId(currentItem);
    const state = itemStates[itemId];

    // Only lock items available for review — scored/completed/in-review items don't need locks
    if (state && state !== ITEM_STATE.DEFAULT) {
      lockReset();
      return;
    }

    lockMutate({ queueId, itemId });

    // Heartbeat at half the TTL to keep the lock alive while viewing
    const heartbeatMs =
      ((annotationQueue.lock_timeout_seconds ?? DEFAULT_LOCK_TIMEOUT_SECONDS) *
        1000) /
      2;
    const heartbeatInterval = setInterval(() => {
      lockMutate({ queueId, itemId });
    }, heartbeatMs);

    return () => {
      clearInterval(heartbeatInterval);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    currentItem,
    queueId,
    annotationQueue?.id,
    annotationQueue?.lock_timeout_seconds,
    lockMutate,
    lockReset,
  ]);

  // --- Initialize annotation state when navigating to a different item ---

  useEffect(() => {
    if (!currentItem) {
      activeItemIdRef.current = undefined;
      setCurrentAnnotationState({ scores: [] });
      resetLastSaved(undefined, []);
      return;
    }

    const itemId = getAnnotationQueueItemId(currentItem);
    if (itemId === activeItemIdRef.current) return;
    activeItemIdRef.current = itemId;

    const lastComment = getLastCommentByUser(
      currentItem.comments,
      currentUserName,
    );
    const userFeedbackScores = getFeedbackScoresByUser(
      currentItem.feedback_scores || [],
      currentUserName,
      annotationQueue?.feedback_definition_names ?? [],
    );

    const comment = lastComment ? { text: lastComment.text } : undefined;

    setCurrentAnnotationState({ comment, scores: userFeedbackScores });
    resetLastSaved(
      lastComment ? { id: lastComment.id, text: lastComment.text } : undefined,
      userFeedbackScores,
    );
  }, [
    currentItem,
    currentUserName,
    annotationQueue?.feedback_definition_names,
    resetLastSaved,
  ]);

  // --- Auto-set initial view ---

  useEffect(() => {
    if (!currentView && !isItemsLoading && queueItems.length > 0) {
      if (defaultItemIds.length === 0) {
        setCurrentView(WORKFLOW_STATUS.COMPLETED);
      } else {
        setCurrentView(WORKFLOW_STATUS.INITIAL);
      }
    }
  }, [
    isItemsLoading,
    queueItems.length,
    defaultItemIds.length,
    currentView,
    setCurrentView,
  ]);

  // --- Context value ---

  const currentItemLockDenied = lockMutation.data?.acquired === false;

  const contextValue: SMEFlowContextValue = useMemo(
    () => ({
      annotationQueue,
      queueItems,
      currentIndex,
      currentItem,
      nextDefaultItem,
      currentView: currentView || WORKFLOW_STATUS.INITIAL,
      processedCount,
      totalCount,
      canStartAnnotation,
      itemStates,
      shuffledItemIds,
      currentAnnotationState,
      currentItemLockDenied,
      setCurrentView,
      handleStartAnnotating,
      handleReviewAnnotations,
      handleNextDefault,
      navigateToItem,
      flushPendingChanges,
      updateComment,
      updateFeedbackScore,
      deleteFeedbackScore,
      isLoading,
      isError,
      isItemsLoading,
      isItemsError,
    }),
    [
      annotationQueue,
      queueItems,
      currentIndex,
      currentItem,
      nextDefaultItem,
      currentView,
      processedCount,
      totalCount,
      canStartAnnotation,
      itemStates,
      shuffledItemIds,
      currentAnnotationState,
      currentItemLockDenied,
      setCurrentView,
      handleStartAnnotating,
      handleReviewAnnotations,
      handleNextDefault,
      navigateToItem,
      flushPendingChanges,
      updateComment,
      updateFeedbackScore,
      deleteFeedbackScore,
      isLoading,
      isError,
      isItemsLoading,
      isItemsError,
    ],
  );

  return (
    <SMEFlowContext.Provider value={contextValue}>
      {children}
    </SMEFlowContext.Provider>
  );
};

export default SMEFlowProvider;
