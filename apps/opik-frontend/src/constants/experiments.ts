import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { EVALUATION_METHOD } from "@/types/datasets";
import {
  COLUMN_EXPERIMENT_SCORES_ID,
  COLUMN_FEEDBACK_SCORES_ID,
} from "@/types/shared";
import { TagProps } from "@/ui/tag";

export const EXPERIMENT_ITEM_OUTPUT_PREFIX = "output";
export const EXPERIMENT_ITEM_DATASET_PREFIX = "data";
export const EXPERIMENT_ITEM_INPUT_PREFIX = "input";
export const EXPERIMENT_ITEM_METADATA_PREFIX = "metadata";

export const OPTIMIZATION_METADATA_EXCLUDED_KEY = "configuration";
export const OPTIMIZATION_OPTIMIZER_KEY = "optimizer";
export const OPTIMIZATION_PROMPT_KEY = `${OPTIMIZATION_METADATA_EXCLUDED_KEY}.prompt`;
export const OPTIMIZATION_EXAMPLES_KEY = `${OPTIMIZATION_METADATA_EXCLUDED_KEY}.examples`;

// TODO: OPIK-5724 — replace with a dedicated BE count endpoint / dataset_item_id filter
export const COMPARE_EXPERIMENTS_MAX_PAGE_SIZE = 20000;

export const STILL_RUNNING_REFRESH_MESSAGE =
  "Still running on the server, refresh to update";

export const ASSERTION_POLL_INTERVAL_MS = 1000;
export const EXPERIMENT_POLL_INTERVAL_MS = 2000;

export const STATUS_TO_VARIANT_MAP: Record<
  OPTIMIZATION_STATUS,
  TagProps["variant"]
> = {
  [OPTIMIZATION_STATUS.RUNNING]: "green",
  [OPTIMIZATION_STATUS.COMPLETED]: "gray",
  [OPTIMIZATION_STATUS.CANCELLED]: "red",
  [OPTIMIZATION_STATUS.INITIALIZED]: "blue",
  [OPTIMIZATION_STATUS.ERROR]: "red",
};

export const COLUMN_DATASET_VERSION_ID = "dataset_version";
export const COLUMN_PASS_RATE_ID = "pass_rate";

export type ExperimentsTabStorage = {
  storageKeyPrefix: string;
  // Columns the tab never shows. An id also covers its per-score columns, so
  // "feedback_scores" hides "feedback_scores.accuracy" too.
  excludedColumnIds: string[];
};

// Shared by the Experiments page tabs and the ls-migration that carries the
// pre-split "experiments-*" settings into them.
export const EXPERIMENTS_TAB_STORAGE: Record<
  EVALUATION_METHOD,
  ExperimentsTabStorage
> = {
  [EVALUATION_METHOD.TEST_SUITE]: {
    storageKeyPrefix: "experiments-test-suites",
    excludedColumnIds: [COLUMN_FEEDBACK_SCORES_ID, COLUMN_EXPERIMENT_SCORES_ID],
  },
  [EVALUATION_METHOD.DATASET]: {
    storageKeyPrefix: "experiments-datasets",
    excludedColumnIds: [COLUMN_PASS_RATE_ID],
  },
};

export const isExcludedTabColumn = (
  columnId: string,
  excludedColumnIds: string[],
) =>
  excludedColumnIds.some(
    (excludedId) =>
      columnId === excludedId || columnId.startsWith(`${excludedId}.`),
  );
