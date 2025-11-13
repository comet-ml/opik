/**
 * Utility functions for optimization chart data processing
 */

export type FeedbackScore = {
  name: string;
  value: number;
};

export type DataRecord = {
  entityId: string;
  entityName: string;
  createdDate: string;
  value: number | null;
  allFeedbackScores?: FeedbackScore[];
};

// Color for the main objective - always uses this color for prominence
const MAIN_OBJECTIVE_COLOR = "var(--color-blue)";

// Available chart colors for secondary scores in order of visual distinction
const SECONDARY_SCORE_COLORS = [
  "var(--color-orange)",
  "var(--color-green)",
  "var(--color-purple)",
  "var(--color-pink)",
  "var(--color-turquoise)",
  "var(--color-yellow)",
  "var(--color-burgundy)",
];

/**
 * Extract all unique feedback score names from the data records,
 * excluding the main objective, sorted alphabetically
 */
export const extractSecondaryScoreNames = (
  data: DataRecord[],
  mainObjective: string,
): string[] => {
  const scoreNamesSet = new Set<string>();

  data.forEach((record) => {
    record.allFeedbackScores?.forEach((score) => {
      if (score.name !== mainObjective) {
        scoreNamesSet.add(score.name);
      }
    });
  });

  // Sort alphabetically for consistent display order
  return Array.from(scoreNamesSet).sort((a, b) =>
    a.localeCompare(b, undefined, { sensitivity: "base" }),
  );
};

/**
 * Get the value for a specific feedback score from a record
 */
export const getScoreValue = (
  record: DataRecord,
  scoreName: string,
): number | null => {
  if (!record.allFeedbackScores) return null;

  const score = record.allFeedbackScores.find((s) => s.name === scoreName);
  return score ? score.value : null;
};

/**
 * Generate a color map ensuring main objective and secondary scores have distinct colors
 * Secondary scores are sorted alphabetically to guarantee consistent color assignment
 */
export const generateDistinctColorMap = (
  mainObjective: string,
  secondaryScores: string[],
): Record<string, string> => {
  const colorMap: Record<string, string> = {};

  // Assign main objective color
  colorMap[mainObjective] = MAIN_OBJECTIVE_COLOR;

  // Sort secondary scores alphabetically to ensure consistent color assignment
  const sortedSecondaryScores = [...secondaryScores].sort((a, b) =>
    a.localeCompare(b, undefined, { sensitivity: "base" }),
  );

  // Assign colors to secondary scores from the secondary color palette
  sortedSecondaryScores.forEach((scoreName, index) => {
    // Use modulo to cycle through colors if we have more scores than colors
    colorMap[scoreName] =
      SECONDARY_SCORE_COLORS[index % SECONDARY_SCORE_COLORS.length];
  });

  return colorMap;
};
