/**
 * TypeScript interfaces for custom prompt configuration
 */

/**
 * Variable definition for template prompts
 */
export interface PromptVariable {
  /** Variable name without braces, e.g. "data_summary" */
  name: string;
  /** Human-readable description */
  description: string;
}

/**
 * Custom prompt configuration stored in localStorage
 */
export interface CustomPromptConfig {
  /** Conversational AI prompt (null = use default) */
  conversationalPrompt: string | null;
  /** Schema generation AI prompt (null = use default) */
  schemaGenerationPrompt: string | null;
}

/**
 * Props for the SystemPromptEditor component
 */
export interface SystemPromptEditorProps {
  /** Current prompt value */
  value: string;
  /** Callback when value changes */
  onChange: (value: string) => void;
  /** Placeholder text */
  placeholder?: string;
  /** Whether the editor is read-only */
  readOnly?: boolean;
  /** Minimum height of the editor */
  minHeight?: string;
}

/**
 * Props for the PromptVariablesPanel component
 */
export interface PromptVariablesPanelProps {
  /** List of available variables */
  variables: PromptVariable[];
  /** Callback when a variable is clicked for insertion */
  onInsertVariable: (variableName: string) => void;
}

/**
 * Props for prompt dialog components
 */
export interface PromptDialogProps {
  /** Whether the dialog is open */
  open: boolean;
  /** Callback to close the dialog */
  onOpenChange: (open: boolean) => void;
  /** Project ID for storage key */
  projectId: string;
  /** Current prompt value */
  currentPrompt: string | null;
  /** Callback when prompt is saved */
  onSave: (prompt: string | null) => void;
}

/**
 * Return type for useCustomPrompts hook
 */
export interface UseCustomPromptsReturn {
  /** Custom conversational prompt (null = use default) */
  conversationalPrompt: string | null;
  /** Custom schema generation prompt (null = use default) */
  schemaGenerationPrompt: string | null;
  /** Whether conversational prompt is customized */
  isConversationalCustomized: boolean;
  /** Whether schema generation prompt is customized */
  isSchemaGenerationCustomized: boolean;
  /** Save conversational prompt (null to reset to default) */
  setConversationalPrompt: (prompt: string | null) => void;
  /** Save schema generation prompt (null to reset to default) */
  setSchemaGenerationPrompt: (prompt: string | null) => void;
  /** Reset all prompts to defaults */
  resetAll: () => void;
}
