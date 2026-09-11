export enum VersionStage {
  DEV = "dev",
  STAGING = "staging",
  PROD = "prod",
}

const STAGE_ORDER = [VersionStage.PROD, VersionStage.STAGING, VersionStage.DEV];

export const isProdTag = (tag: string) => tag === VersionStage.PROD;

export const isBasicStage = (tag: string) => STAGE_ORDER.some((s) => s === tag);

export const pickHighestStage = (
  tags: string[] | undefined,
): VersionStage | undefined =>
  STAGE_ORDER.find((stage) => tags?.some((t) => t === stage));

export const sortTags = (tags: string[]) => [
  ...STAGE_ORDER.filter((stage) => tags.some((t) => t === stage)),
  ...tags.filter((t) => !isBasicStage(t)),
];
