export const DATASET_ITEM_DATA_PREFIX = "data";

export const LATEST_VERSION_TAG = "latest";

export const isLatestVersionTag = (tag: string): boolean =>
  tag.toLowerCase() === LATEST_VERSION_TAG;

export const filterOutLatestTag = (tags: string[]): string[] =>
  tags.filter((tag) => !isLatestVersionTag(tag));
