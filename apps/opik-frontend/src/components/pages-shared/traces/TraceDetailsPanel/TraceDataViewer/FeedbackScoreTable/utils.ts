import {
  FeedbackScoreValueByAuthorMap,
  TraceFeedbackScore,
} from "@/types/traces";
import { ExpandingFeedbackScoreRow } from "./types";
import { getIsMultiValueFeedbackScore } from "@/lib/feedback-scores";
import { PARENT_ROW_ID_PREFIX } from "./constants";

/**
 * Extracts the author name from a composite key (author_spanId) or returns the key as-is.
 * This is used for span feedback scores where the backend uses composite keys to uniquely identify
 * each span's feedback score even when multiple spans have the same author.
 *
 * The format is guaranteed to be `author_spanId`, so we extract the author part by splitting
 * from the right (using lastIndexOf) to handle author names that may contain underscores.
 */
export const extractAuthorName = (key: string): string => {
  // Extract just the author part before the last underscore
  const lastUnderscore = key.lastIndexOf("_");
  if (lastUnderscore !== -1) {
    return key.substring(0, lastUnderscore);
  }
  return key;
};

/**
 * Creates a child row from a parent feedback score and individual score entry.
 * Handles extraction of author name, setting individual values, and span metadata.
 */
const createChildRow = (
  parentId: string,
  parentScore: TraceFeedbackScore,
  authorKey: string,
  score: FeedbackScoreValueByAuthorMap[string],
  options: {
    index?: number;
    spanType?: string;
    isSpanFeedbackScores?: boolean;
    useAuthorKeyInId?: boolean; // For trace feedback scores, use authorKey directly in ID
  } = {},
): ExpandingFeedbackScoreRow => {
  const {
    index,
    spanType,
    isSpanFeedbackScores = false,
    useAuthorKeyInId = false,
  } = options;
  const authorName = isSpanFeedbackScores
    ? extractAuthorName(authorKey)
    : authorKey;

  // Generate ID based on context
  let id: string;
  if (spanType) {
    // Grouped by span type
    id = `${parentId}-${spanType}-${score.span_id || index || 0}`;
  } else if (useAuthorKeyInId) {
    // For trace feedback scores (legacy format)
    id = `${parentId}${authorKey}`;
  } else {
    // Default format for span feedback scores
    id = `${parentId}-${score.span_id || authorName || index || 0}`;
  }

  const baseRow: ExpandingFeedbackScoreRow = {
    id,
    ...parentScore,
    value: score.value,
    category_name: score.category_name,
    value_by_author: isSpanFeedbackScores
      ? { [authorKey]: score }
      : parentScore.value_by_author,
    name: parentScore.name,
    author: authorName,
  };

  // Add span metadata if this is a span feedback score
  if (isSpanFeedbackScores) {
    baseRow.span_id = score.span_id;
    baseRow.span_type = spanType || score.span_type;
  }

  return baseRow;
};

/**
 * Extracts span_id and span_type from the first entry in value_by_author.
 * Used for single-value feedback scores where we need to get span metadata.
 */
export const extractSpanMetadataFromValueByAuthor = (
  valueByAuthor?: FeedbackScoreValueByAuthorMap,
): { span_id?: string; span_type?: string } => {
  if (!valueByAuthor) {
    return {};
  }
  const firstEntry = Object.values(valueByAuthor)[0];
  return {
    span_id: firstEntry?.span_id,
    span_type: firstEntry?.span_type,
  };
};

const getUniqueSpanTypes = (
  valueByAuthor: FeedbackScoreValueByAuthorMap,
): string[] => {
  const types = new Set<string>();
  Object.values(valueByAuthor).forEach((score) => {
    if (score.span_type) {
      types.add(score.span_type);
    }
  });
  return Array.from(types);
};

const hasMultipleSpanTypes = (
  valueByAuthor: FeedbackScoreValueByAuthorMap,
): boolean => {
  return getUniqueSpanTypes(valueByAuthor).length > 1;
};

const getUniqueSpanIds = (
  valueByAuthor: FeedbackScoreValueByAuthorMap,
): string[] => {
  const ids = new Set<string>();
  Object.values(valueByAuthor).forEach((score) => {
    if (score.span_id) {
      ids.add(score.span_id);
    }
  });
  return Array.from(ids);
};

const hasMultipleSpans = (
  valueByAuthor: FeedbackScoreValueByAuthorMap,
): boolean => {
  return getUniqueSpanIds(valueByAuthor).length > 1;
};

export const mapFeedbackScoresToRowsWithExpanded = (
  feedbackScores: TraceFeedbackScore[],
  entityType: "trace" | "thread" | "span" | "experiment" = "trace",
  isAggregatedSpanScores: boolean = false,
): ExpandingFeedbackScoreRow[] => {
  const rows: ExpandingFeedbackScoreRow[] = [];
  // Only treat as span feedback scores when showing aggregated span scores at trace level
  // Individual span scores don't need parent/child rows
  const isSpanFeedbackScores = isAggregatedSpanScores && entityType === "span";

  feedbackScores.forEach((feedbackScore) => {
    const hasMultiValue = getIsMultiValueFeedbackScore(
      feedbackScore.value_by_author,
    );
    const valueByAuthor = feedbackScore.value_by_author ?? {};

    // For span feedback scores displayed at trace level, they are always aggregated from multiple spans
    // So we should create parent/child rows whenever we have multiple values OR multiple spans detected
    // Note: When multiple spans have the same author, backend's mapFromArrays collapses them,
    // but we still want to show grouping if we detect multiple span_ids or if value suggests aggregation
    const hasMultipleSpansDetected = hasMultipleSpans(valueByAuthor);

    // Check if value has decimal places (indicating it's an average from multiple spans)
    const valueAsNumber =
      typeof feedbackScore.value === "string"
        ? parseFloat(feedbackScore.value)
        : Number(feedbackScore.value);
    const hasDecimalValue =
      !isNaN(valueAsNumber) &&
      isFinite(valueAsNumber) &&
      valueAsNumber % 1 !== 0;

    // For span feedback scores, create parent/child rows if:
    // 1. We have multiple values (different authors), OR
    // 2. We detect multiple spans (different span_ids in value_by_author), OR
    // 3. The value has decimal places (indicating it's an average from multiple spans)
    // This ensures grouping works even when spans have the same author
    const hasMultiSpansForScore =
      isSpanFeedbackScores &&
      (hasMultiValue || hasMultipleSpansDetected || hasDecimalValue);
    const hasMultiSpanTypes =
      isSpanFeedbackScores &&
      hasMultiValue &&
      hasMultipleSpanTypes(valueByAuthor);

    // Group by span type if we have multiple span types
    if (hasMultiSpanTypes) {
      const parentId = `${PARENT_ROW_ID_PREFIX}${feedbackScore.name}`;
      const parentRow: ExpandingFeedbackScoreRow = {
        id: parentId,
        ...feedbackScore,
        subRows: [],
      };

      rows.push(parentRow);

      // Group entries by span_type
      const groupedByType = new Map<
        string,
        typeof feedbackScore.value_by_author
      >([]);

      Object.entries(feedbackScore.value_by_author ?? {}).forEach(
        ([author, score]) => {
          const spanType = score.span_type || "general";
          if (!groupedByType.has(spanType)) {
            groupedByType.set(spanType, {});
          }
          groupedByType.get(spanType)![author] = score;
        },
      );

      // Create child rows grouped by span type
      // Each child row represents one span with that type
      parentRow.subRows = Array.from(groupedByType.entries()).flatMap(
        ([spanType, typeScores]) => {
          // Create one row per span (each span has one feedback score per name)
          if (!typeScores) return [];
          return Object.entries(typeScores).map(([authorKey, score], index) =>
            createChildRow(parentId, feedbackScore, authorKey, score, {
              index,
              spanType,
              isSpanFeedbackScores: true,
            }),
          );
        },
      );

      return;
    }

    // For span feedback scores with multiple spans (same type), create parent/child rows
    // Also handle cases where we have multiple values (even if same author) or multiple spans
    if (hasMultiSpansForScore) {
      const parentId = `${PARENT_ROW_ID_PREFIX}${feedbackScore.name}`;
      const parentRow: ExpandingFeedbackScoreRow = {
        id: parentId,
        ...feedbackScore,
        subRows: [],
      };

      rows.push(parentRow);

      // Create child rows for each individual span
      // If span_id is available, use it to create unique rows
      // Otherwise, use author as fallback
      const entries = Object.entries(feedbackScore.value_by_author ?? {});

      // If we have span_ids, create one row per span_id
      // Otherwise, create one row per author entry
      parentRow.subRows = entries.map(([authorKey, score], index) =>
        createChildRow(parentId, feedbackScore, authorKey, score, {
          index,
          isSpanFeedbackScores: true,
        }),
      );

      return;
    }

    // Original logic for multiple authors (not grouped by type or spans)
    if (hasMultiValue) {
      const parentId = `${PARENT_ROW_ID_PREFIX}${feedbackScore.name}`;
      const parentRow: ExpandingFeedbackScoreRow = {
        id: parentId,
        ...feedbackScore,
        subRows: [],
      };

      rows.push(parentRow);

      parentRow.subRows = Object.entries(
        feedbackScore.value_by_author ?? {},
      ).map(([authorKey, score], index) =>
        createChildRow(parentId, feedbackScore, authorKey, score, {
          index,
          isSpanFeedbackScores,
          useAuthorKeyInId: !isSpanFeedbackScores, // Use authorKey format for trace feedback scores
        }),
      );

      return;
    }

    // For single-value rows, extract span_id and span_type if available
    const spanMetadata = isSpanFeedbackScores
      ? extractSpanMetadataFromValueByAuthor(feedbackScore.value_by_author)
      : {};

    rows.push({
      id: feedbackScore.name,
      author: feedbackScore.created_by,
      ...feedbackScore,
      ...spanMetadata,
    });
  });

  return rows;
};

export const getIsParentFeedbackScoreRow = (
  row: ExpandingFeedbackScoreRow,
): row is ExpandingFeedbackScoreRow & {
  value_by_author: FeedbackScoreValueByAuthorMap;
} => {
  return (
    (getIsMultiValueFeedbackScore(row.value_by_author) ||
      Boolean(row.subRows?.length)) &&
    !row.author
  );
};
