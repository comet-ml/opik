/**
 * Single Source of Truth for resource colors in navigation tags and date tags.
 * Use these colors for NavigationTag and DateTag components.
 */

export enum RESOURCE_TYPE {
  project = "project",
  dataset = "dataset",
  prompt = "prompt",
  experiment = "experiment",
  optimization = "optimization",
  trial = "trial",
  annotationQueue = "annotationQueue",
}

/**
 * Color mapping for resources.
 * Colors use CSS custom properties defined in the theme.
 */
export const RESOURCE_COLORS: Record<RESOURCE_TYPE, string> = {
  [RESOURCE_TYPE.project]: "var(--color-blue)",
  [RESOURCE_TYPE.dataset]: "var(--color-yellow)",
  [RESOURCE_TYPE.prompt]: "var(--color-purple)",
  [RESOURCE_TYPE.experiment]: "var(--color-green)",
  [RESOURCE_TYPE.optimization]: "var(--color-yellow)",
  [RESOURCE_TYPE.trial]: "var(--color-yellow)",
  [RESOURCE_TYPE.annotationQueue]: "var(--color-pink)",
};
