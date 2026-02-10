/**
 * Public exports for custom prompt configuration components
 */

// Types
export type {
  PromptVariable,
  CustomPromptConfig,
  SystemPromptEditorProps,
  PromptVariablesPanelProps,
  PromptDialogProps,
  UseCustomPromptsReturn,
} from "./types";

// Constants
export {
  DEFAULT_CONVERSATIONAL_PROMPT,
  DEFAULT_SCHEMA_GENERATION_PROMPT,
  CONVERSATIONAL_VARIABLES,
  SCHEMA_GENERATION_VARIABLES,
  getPromptsStorageKey,
} from "./promptConstants";

// Hook
export {
  useCustomPrompts,
  default as useCustomPromptsHook,
} from "./useCustomPrompts";

// Components
export { default as SystemPromptEditor } from "./SystemPromptEditor";
export type { SystemPromptEditorHandle } from "./SystemPromptEditor";
export { default as PromptVariablesPanel } from "./PromptVariablesPanel";
export { default as ConversationalPromptDialog } from "./ConversationalPromptDialog";
export { default as SchemaGenerationPromptDialog } from "./SchemaGenerationPromptDialog";
