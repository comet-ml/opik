/**
 * Single Source of Truth for entity colors across the application.
 * Use these colors for sidebar icons, navigation tags, and any other entity representations.
 */

export enum ENTITY_TYPE {
  // Main resources (have navigation tags)
  project = "project",
  dataset = "dataset",
  prompt = "prompt",
  experiment = "experiment",
  optimization = "optimization",
  trial = "trial",
  annotationQueue = "annotationQueue",

  // Application sections (sidebar only)
  home = "home",
  playground = "playground",
  onlineEvaluation = "onlineEvaluation",
  automations = "automations",
  configuration = "configuration",
}

/**
 * Color mapping for all entities in the system.
 * Colors use CSS custom properties defined in the theme.
 */
export const ENTITY_COLORS: Record<ENTITY_TYPE, string> = {
  // Main resources
  [ENTITY_TYPE.project]: "var(--color-blue)",
  [ENTITY_TYPE.dataset]: "var(--color-yellow)",
  [ENTITY_TYPE.prompt]: "var(--color-purple)",
  [ENTITY_TYPE.experiment]: "var(--color-green)",
  [ENTITY_TYPE.optimization]: "var(--color-turquoise)",
  [ENTITY_TYPE.trial]: "var(--color-turquoise)",
  [ENTITY_TYPE.annotationQueue]: "var(--color-pink)",

  // Application sections
  [ENTITY_TYPE.home]: "var(--color-gray)",
  [ENTITY_TYPE.playground]: "var(--color-burgundy)",
  [ENTITY_TYPE.onlineEvaluation]: "var(--color-orange)",
  [ENTITY_TYPE.automations]: "var(--color-blue)",
  [ENTITY_TYPE.configuration]: "var(--color-gray)",
};

