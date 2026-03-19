export const DATASET_ITEM_DATA_PREFIX = "data";

export const OPIK_DESCRIPTION_FIELD = "_opik_description";
export const OPIK_ASSERTIONS_FIELD = "_opik_evaluator_assertions";
export const OPIK_GENERATION_MODEL_FIELD = "_generation_model";

export const LATEST_VERSION_TAG = "latest";

export const isLatestVersionTag = (tag: string): boolean =>
  tag.toLowerCase() === LATEST_VERSION_TAG;

export const filterOutLatestTag = (tags: string[]): string[] =>
  tags.filter((tag) => !isLatestVersionTag(tag));
