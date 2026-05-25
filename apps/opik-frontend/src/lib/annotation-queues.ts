import { Thread, Trace, TraceFeedbackScore } from "@/types/traces";
import get from "lodash/get";
import isNumber from "lodash/isNumber";
import omit from "lodash/omit";
import { isObjectThread } from "@/lib/traces";
import { CommentItem, CommentItems } from "@/types/comment";
import { findValueByAuthor, hasValuesByAuthor } from "@/lib/feedback-scores";

export const generateSMEURL = (workspace: string, id: string): string => {
  const basePath = import.meta.env.VITE_BASE_URL || "/";
  const relativePath = `${workspace}/sme?queueId=${id}`;

  const normalizedBasePath =
    basePath === "/" ? "" : basePath.replace(/\/$/, "");
  const fullPath = `${normalizedBasePath}/${relativePath}`;
  return new URL(fullPath, window.location.origin).toString();
};

export const generateTracesURL = (
  workspace: string,
  projectId: string,
  type: "traces" | "threads",
  id: string,
): string => {
  const basePath = import.meta.env.VITE_BASE_URL || "/";
  const queryParam = type === "traces" ? `trace=${id}` : `thread=${id}`;
  const relativePath = `${workspace}/projects/${projectId}/traces?tab=logs&logsType=${type}&${queryParam}`;

  const normalizedBasePath =
    basePath === "/" ? "" : basePath.replace(/\/$/, "");
  const fullPath = `${normalizedBasePath}/${relativePath}`;
  return new URL(fullPath, window.location.origin).toString();
};

export const getAnnotationQueueItemId = (item: Trace | Thread) =>
  isObjectThread(item) ? get(item, "thread_model_id", "") : get(item, "id", "");

export const getFeedbackScoresByUser = (
  feedbackScores: TraceFeedbackScore[],
  userName: string | undefined,
  feedbackDefinitionNames: string[],
): TraceFeedbackScore[] => {
  if (!userName) return [];

  return feedbackScores
    .filter(
      (feedbackScore) =>
        feedbackScore && feedbackDefinitionNames.includes(feedbackScore.name),
    )
    .map((feedbackScore): TraceFeedbackScore | undefined => {
      if (hasValuesByAuthor(feedbackScore)) {
        const userValue = findValueByAuthor(
          feedbackScore.value_by_author,
          userName,
        );
        if (userValue) {
          const rawValue = userValue.value;
          return {
            name: feedbackScore.name,
            value: isNumber(rawValue) ? rawValue : 0,
            reason: userValue.reason ?? "",
            category_name: userValue.category_name ?? "",
            source: userValue.source,
            created_by: userName,
            last_updated_at: userValue.last_updated_at,
            last_updated_by: userName,
          };
        }
      } else if (userName === feedbackScore.last_updated_by) {
        return omit(feedbackScore, "value_by_author");
      }
    })
    .filter((score): score is TraceFeedbackScore => score !== undefined);
};

export const getLastCommentByUser = (
  comments: CommentItems | undefined,
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

export const getCommentsByUser = (
  comments: CommentItems | undefined,
  userName: string | undefined,
): string[] => {
  if (!comments || !userName) return [];

  return comments
    .filter((comment) => comment.created_by === userName)
    .map((comment) => comment.text);
};

export type AnnotationState = {
  comment?: { text?: string };
  scores: TraceFeedbackScore[];
};

export enum ITEM_STATE {
  DEFAULT = "default",
  SCORED = "scored",
  COMPLETED = "completed",
}

export const isItemProcessedByUser = (
  item: Trace | Thread,
  feedbackScoreNames: string[],
  userName: string | undefined,
): boolean => {
  if (!userName) return false;

  const hasFeedbackScores = (item.feedback_scores || []).some((score) => {
    if (!feedbackScoreNames.includes(score.name)) return false;
    return hasValuesByAuthor(score)
      ? Boolean(findValueByAuthor(score.value_by_author, userName))
      : score.last_updated_by === userName;
  });

  const hasComment = (item.comments || []).some(
    (comment) => comment.created_by === userName,
  );

  return hasFeedbackScores || hasComment;
};

export const getDistinctAnnotatorCount = (
  item: Trace | Thread,
  feedbackScoreNames: string[],
): number => {
  const authors = new Set<string>();

  (item.feedback_scores || []).forEach((score) => {
    if (!feedbackScoreNames.includes(score.name)) return;
    if (hasValuesByAuthor(score)) {
      Object.keys(score.value_by_author).forEach((a) => authors.add(a));
    } else if (score.last_updated_by) {
      authors.add(score.last_updated_by);
    }
  });

  (item.comments || []).forEach((comment) => {
    if (comment.created_by) authors.add(comment.created_by);
  });

  return authors.size;
};

export const getItemState = (
  item: Trace | Thread,
  feedbackScoreNames: string[],
  userName: string | undefined,
  annotatorsPerItem: number,
): ITEM_STATE => {
  if (
    getDistinctAnnotatorCount(item, feedbackScoreNames) >= annotatorsPerItem
  ) {
    return ITEM_STATE.COMPLETED;
  }
  if (isItemProcessedByUser(item, feedbackScoreNames, userName)) {
    return ITEM_STATE.SCORED;
  }
  return ITEM_STATE.DEFAULT;
};

export const formatFeedbackScoresForExport = (
  feedbackScores: TraceFeedbackScore[],
): Array<{ name: string; value: number; reason?: string }> => {
  return feedbackScores.map((score) => ({
    name: score.name,
    value: score.value,
    reason: score.reason,
  }));
};
