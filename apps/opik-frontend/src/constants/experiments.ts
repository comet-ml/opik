import { OPTIMIZATION_STATUS } from "@/types/optimizations";
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
