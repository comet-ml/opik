import { useState, useMemo, useCallback } from "react";
import { Trace, Thread } from "@/types/traces";
import { AnnotationQueue } from "@/types/annotation-queues";
import useAnnotationFeedbackSubmissionMutation from "@/api/annotation-queues/useAnnotationFeedbackSubmissionMutation";
import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";

interface FeedbackScore {
  name: string;
  value: number | null;
  type: "range" | "binary" | "categorical";
  min?: number;
  max?: number;
  options?: Array<{ label: string; value: number }>;
  description?: string;
}

interface UseAnnotationFormParams {
  annotationQueue: AnnotationQueue;
  unprocessedItems: (Trace | Thread)[];
  currentItem?: Trace | Thread;
  currentIndex: number;
  onPrevious: () => void;
  onSkip: () => void;
  onSubmitNext: () => void;
}

export const useAnnotationForm = ({
  annotationQueue,
  unprocessedItems,
  currentItem,
  currentIndex,
  onPrevious,
  onSkip,
  onSubmitNext,
}: UseAnnotationFormParams) => {
  const [comment, setComment] = useState("");
  const [feedbackScores, setFeedbackScores] = useState<FeedbackScore[]>([]);

  // Initialize feedback scores based on annotation queue definitions
  useMemo(() => {
    if (annotationQueue?.feedback_definitions) {
      const scores: FeedbackScore[] = annotationQueue.feedback_definitions.map(
        (def) => ({
          name: def.name,
          value: null,
          type: def.type as "range" | "binary" | "categorical",
          min: def.min,
          max: def.max,
          options: def.options,
          description: def.description,
        }),
      );
      setFeedbackScores(scores);
    }
  }, [annotationQueue?.feedback_definitions]);

  const mutation = useAnnotationFeedbackSubmissionMutation();

  const isFirstItem = currentIndex === 0;
  const isLastItem = currentIndex === unprocessedItems.length - 1;
  const isSubmitting = mutation.isPending;

  const hasRequiredFeedback = useMemo(() => {
    return feedbackScores.some((score) => score.value !== null);
  }, [feedbackScores]);

  const handleCommentChange = useCallback((value: string) => {
    setComment(value);
  }, []);

  const handleScoreChange = useCallback((scoreIndex: number, value: number) => {
    setFeedbackScores((prev) =>
      prev.map((score, index) =>
        index === scoreIndex ? { ...score, value } : score,
      ),
    );
  }, []);

  const handleSkip = useCallback(() => {
    // Reset form state
    setComment("");
    setFeedbackScores((prev) =>
      prev.map((score) => ({ ...score, value: null })),
    );
    onSkip();
  }, [onSkip]);

  const handleSubmitNext = useCallback(async () => {
    if (!currentItem || !hasRequiredFeedback) return;

    try {
      // Prepare feedback scores for submission
      const scoresToSubmit = feedbackScores
        .filter((score) => score.value !== null)
        .map((score) => ({
          name: score.name,
          value: score.value!,
        }));

      await mutation.mutateAsync({
        item: currentItem,
        feedbackScores: scoresToSubmit,
        comment: comment.trim() || undefined,
        annotationQueueId: annotationQueue.id,
        projectName: annotationQueue.project_name,
        scope: annotationQueue.scope,
      });

      // Reset form state
      setComment("");
      setFeedbackScores((prev) =>
        prev.map((score) => ({ ...score, value: null })),
      );

      onSubmitNext();
    } catch (error) {
      // Error is handled by the mutation
      console.error("Failed to submit feedback:", error);
    }
  }, [
    currentItem,
    hasRequiredFeedback,
    feedbackScores,
    comment,
    annotationQueue,
    mutation,
    onSubmitNext,
  ]);

  return {
    comment,
    feedbackScores,
    isSubmitting,
    isFirstItem,
    isLastItem,
    hasRequiredFeedback,
    isFeedbackDefinitionsLoading: false, // We have the definitions from annotationQueue
    handleCommentChange,
    handleScoreChange,
    handleSkip,
    handleSubmitNext,
  };
};
