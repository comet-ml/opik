import { useCallback, useRef } from "react";
import cloneDeep from "lodash/cloneDeep";
import { Trace, Thread, TraceFeedbackScore } from "@/types/traces";
import {
  AnnotationQueue,
  ANNOTATION_QUEUE_SCOPE,
} from "@/types/annotation-queues";
import { getAnnotationQueueItemId } from "@/lib/annotation-queues";
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
import { AnnotationState } from "@/lib/annotation-queues";

const DEBOUNCE_MS = 1000;

interface LastSavedComment {
  id?: string;
  text: string;
}

interface UseAnnotationPersistenceParams {
  annotationQueue: AnnotationQueue | undefined;
  annotationStateRef: React.RefObject<AnnotationState>;
  currentItemRef: React.RefObject<Trace | Thread | undefined>;
}

export function useAnnotationPersistence({
  annotationQueue,
  annotationStateRef,
  currentItemRef,
}: UseAnnotationPersistenceParams) {
  const isThread = annotationQueue?.scope === ANNOTATION_QUEUE_SCOPE.THREAD;

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

  const lastSavedCommentRef = useRef<LastSavedComment>({ text: "" });
  const lastSavedScoresRef = useRef<TraceFeedbackScore[]>([]);
  const commentCreatingRef = useRef(false);
  const ignoreNextCreateResponseRef = useRef(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const scheduleSaveRef = useRef<() => void>(() => {});

  // Captures the comment ID from the CREATE response so subsequent edits
  // send UPDATE instead of duplicate CREATEs. If the user navigated to a
  // different item before the response arrives, ignoreNextCreateResponseRef
  // is set by resetLastSaved and we skip to avoid corrupting the new item's state.
  const onCommentCreated = useCallback((data: { id?: string }) => {
    commentCreatingRef.current = false;
    if (ignoreNextCreateResponseRef.current) {
      ignoreNextCreateResponseRef.current = false;
      return;
    }
    if (data?.id) {
      lastSavedCommentRef.current.id = data.id;
    }
    scheduleSaveRef.current();
  }, []);

  const onCommentCreateError = useCallback(() => {
    commentCreatingRef.current = false;
    lastSavedCommentRef.current = { text: "" };
    if (ignoreNextCreateResponseRef.current) {
      ignoreNextCreateResponseRef.current = false;
      return;
    }
    scheduleSaveRef.current();
  }, []);

  const saveNow = useCallback(() => {
    const state = annotationStateRef.current;
    const item = currentItemRef.current;
    if (!state || !item) return;

    // --- Comments ---
    const currentText = state.comment?.text?.trim() || "";
    const { id: lastId, text: lastText } = lastSavedCommentRef.current;

    if (currentText !== lastText && !commentCreatingRef.current) {
      if (currentText && !lastId) {
        commentCreatingRef.current = true;
        lastSavedCommentRef.current.text = currentText;
        if (isThread) {
          const thread = item as Thread;
          createThreadComment(
            {
              threadId: getAnnotationQueueItemId(thread),
              projectId: thread.project_id,
              text: currentText,
            },
            { onSuccess: onCommentCreated, onError: onCommentCreateError },
          );
        } else {
          createTraceComment(
            { traceId: item.id, text: currentText },
            { onSuccess: onCommentCreated, onError: onCommentCreateError },
          );
        }
      } else if (currentText && lastId) {
        lastSavedCommentRef.current.text = currentText;
        if (isThread) {
          updateThreadComment({
            commentId: lastId,
            projectId: (item as Thread).project_id,
            text: currentText,
          });
        } else {
          updateTraceComment({
            commentId: lastId,
            traceId: item.id,
            text: currentText,
          });
        }
      } else if (!currentText && lastId) {
        lastSavedCommentRef.current = { text: "" };
        if (isThread) {
          const thread = item as Thread;
          deleteThreadComments({
            ids: [lastId],
            threadId: getAnnotationQueueItemId(thread),
            projectId: thread.project_id,
          });
        } else {
          deleteTraceComments({ ids: [lastId], traceId: item.id });
        }
      }
    }

    // --- Scores ---
    const currentScores = state.scores;
    const lastScores = lastSavedScoresRef.current;

    const deletedScores = lastScores.filter(
      (orig) => !currentScores.some((s) => s.name === orig.name),
    );
    const changedScores = currentScores.filter((score) => {
      const orig = lastScores.find((o) => o.name === score.name);
      return (
        !orig ||
        orig.value !== score.value ||
        orig.reason !== score.reason ||
        orig.category_name !== score.category_name
      );
    });

    if (deletedScores.length || changedScores.length) {
      if (isThread) {
        if (!annotationQueue) return;
        const thread = item as Thread;
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
            scores: changedScores.map((s) => ({
              name: s.name,
              categoryName: s.category_name,
              value: s.value,
              reason: s.reason,
            })),
          });
        }
      } else {
        deletedScores.forEach((score) => {
          deleteTraceFeedbackScore({ traceId: item.id, name: score.name });
        });
        changedScores.forEach((score) => {
          setTraceFeedbackScore({
            traceId: item.id,
            name: score.name,
            categoryName: score.category_name,
            value: score.value,
            reason: score.reason,
          });
        });
      }
      lastSavedScoresRef.current = cloneDeep(currentScores);
    }
  }, [
    isThread,
    annotationQueue,
    annotationStateRef,
    currentItemRef,
    createTraceComment,
    createThreadComment,
    updateTraceComment,
    updateThreadComment,
    deleteTraceComments,
    deleteThreadComments,
    setTraceFeedbackScore,
    deleteTraceFeedbackScore,
    setThreadFeedbackScore,
    deleteThreadFeedbackScore,
    onCommentCreated,
    onCommentCreateError,
  ]);

  const scheduleSave = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      saveNow();
    }, DEBOUNCE_MS);
  }, [saveNow]);
  scheduleSaveRef.current = scheduleSave;

  const flushPendingChanges = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    saveNow();
  }, [saveNow]);

  const resetLastSaved = useCallback(
    (
      comment: { id?: string; text?: string } | undefined,
      scores: TraceFeedbackScore[],
    ) => {
      if (timerRef.current) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
      if (commentCreatingRef.current) {
        ignoreNextCreateResponseRef.current = true;
      }
      commentCreatingRef.current = false;
      lastSavedCommentRef.current = {
        id: comment?.id,
        text: comment?.text?.trim() || "",
      };
      lastSavedScoresRef.current = cloneDeep(scores);
    },
    [],
  );

  return { scheduleSave, flushPendingChanges, resetLastSaved };
}
