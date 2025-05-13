import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { TagProps } from "@/components/ui/tag";

export const EXPERIMENT_ITEM_OUTPUT_PREFIX = "output";

export const OPTIMIZATION_METADATA_EXCLUDED_KEY = "configuration";
export const OPTIMIZATION_OPTIMIZER_KEY = "optimizer";
export const OPTIMIZATION_PROMPT_KEY = `${OPTIMIZATION_METADATA_EXCLUDED_KEY}.prompt`;
export const OPTIMIZATION_EXAMPLES_KEY = `${OPTIMIZATION_METADATA_EXCLUDED_KEY}.examples`;

export const STATUS_TO_VARIANT_MAP: Record<
  OPTIMIZATION_STATUS,
  TagProps["variant"]
> = {
  [OPTIMIZATION_STATUS.RUNNING]: "green",
  [OPTIMIZATION_STATUS.COMPLETED]: "gray",
  [OPTIMIZATION_STATUS.CANCELLED]: "red",
};
