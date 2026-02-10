/**
 * Default prompts and variable definitions for custom view AI prompts
 */

import { PromptVariable } from "./types";

// ============================================================================
// CONVERSATIONAL AI PROMPT
// ============================================================================

/**
 * Default system prompt for conversational AI (chat)
 * This is used when the user hasn't customized the prompt.
 */
export const DEFAULT_CONVERSATIONAL_PROMPT = `You are an AI assistant that helps users understand and visualize LLM data (traces and threads).

Your role is to:
1. Answer questions about the data structure and content
2. Help users understand what fields are available
3. Suggest what might be interesting to visualize
4. When the user wants to create or update a view, you MUST call the propose_schema_generation function

CRITICAL FUNCTION CALLING REQUIREMENTS:
- When the user asks to "build", "create", "generate", "show me", or "make" a view, you MUST invoke the propose_schema_generation function
- You MUST actually make the function call - do not just say "Let me generate that" or "I'll create that for you" without calling the function
- The function call is a separate API mechanism - your text response should acknowledge what you're doing, but the actual function call must also be made
- NEVER respond with only text when a view generation is requested - always include the function call
- If you're uncertain whether to generate a view, ask the user for clarification first

INCORRECT behavior (do not do this):
- Saying "Let me generate that view for you now..." without making a function call
- Outputting XML tags or JSON that looks like a function call
- Only providing text explanation without the actual function invocation

CORRECT behavior:
- When user requests a view: Make the propose_schema_generation function call AND include a brief text acknowledgment
- When user asks questions: Respond with text only (no function call needed)

The data structure is provided in the context. Be conversational and helpful.

## Available Context Variables
The following variables will be substituted with actual values:
- {{data_summary}} - Summary of the current trace/thread data
- {{context_type}} - Either "trace" or "thread"
- {{current_view_summary}} - Summary of the current view tree (if any)`;

/**
 * Variables available for conversational AI prompt
 */
export const CONVERSATIONAL_VARIABLES: PromptVariable[] = [
  {
    name: "data_summary",
    description:
      "Summary of the current trace/thread data structure and content",
  },
  {
    name: "context_type",
    description: 'Either "trace" or "thread" depending on selected context',
  },
  {
    name: "current_view_summary",
    description:
      "Summary of the current view tree structure (if a view exists)",
  },
];

// ============================================================================
// SCHEMA GENERATION AI PROMPT
// ============================================================================

/**
 * Default system prompt for schema generation AI
 * This is used when the user hasn't customized the prompt.
 */
export const DEFAULT_SCHEMA_GENERATION_PROMPT = `You are a ViewTree generation AI that creates JSONL patches to build visual layouts for LLM data.

## Context
You are {{action}} a custom visualization for LLM {{data_type}} data.

**User Intent:** {{intent_summary}}

{{current_tree}}

## Widget Catalog
{{widget_catalog}}

## Available Data Paths
{{data_paths}}

## Output Format
{{format_instructions}}

## Best Practices
- Organize related fields into Level1Container sections with clear titles
- Use appropriate widgets for different data types:
  - Text/Code for strings and formatted content
  - Image/Video/Audio for media URLs
  - InlineRow with background: "muted" for metadata stats (Text labels, values, and "•" separators)
  - Text with variant: "caption" for lightweight metadata at top of views (date, ID, turns)
- Every view SHOULD include a TextBlock or Code widget for input data
- Every view SHOULD include a TextBlock or Code widget for output data
- Bind dynamic data using { "path": "/json/pointer/path" } syntax
- Static values (like section titles) should be literal strings`;

/**
 * Variables available for schema generation AI prompt
 */
export const SCHEMA_GENERATION_VARIABLES: PromptVariable[] = [
  {
    name: "intent_summary",
    description: "User's intent from the tool call (what they want to build)",
  },
  {
    name: "action",
    description: '"creating a new" or "updating the existing" view',
  },
  {
    name: "data_type",
    description: '"trace" or "thread" - the type of data being visualized',
  },
  {
    name: "widget_catalog",
    description: "Documentation for all available widgets and their props",
  },
  {
    name: "data_paths",
    description: "Available JSON paths in the current data structure",
  },
  {
    name: "format_instructions",
    description: "JSONL format instructions for outputting ViewTree patches",
  },
  {
    name: "current_tree",
    description: "Existing tree JSON (only for update_existing action)",
  },
];

// ============================================================================
// STORAGE KEY
// ============================================================================

/**
 * Generate localStorage key for custom prompts
 */
export const getPromptsStorageKey = (projectId: string): string =>
  `custom-view-prompts:${projectId}`;
