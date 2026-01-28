/**
 * Schema proposal types for the dual AI architecture
 * Separates conversational AI from schema generation AI
 */

/**
 * Schema action type - what kind of schema operation to perform
 */
export type SchemaAction = "generate_new" | "update_existing";

/**
 * Schema proposal from the Chat AI tool call
 */
export interface SchemaProposal {
  /** Summary of what the user wants to visualize */
  intentSummary: string;
  /** Type of schema operation */
  action: SchemaAction;
  /** Unique ID for this proposal */
  id: string;
}

/**
 * Proposal state machine
 * Controls the UI flow and input blocking
 */
export type ProposalState =
  | { status: "idle" } // Normal chat mode
  | { status: "pending"; proposal: SchemaProposal } // Waiting for user accept/reject
  | { status: "generating"; proposal: SchemaProposal } // Generating schema after accept
  | { status: "rejected"; proposal: SchemaProposal }; // User rejected, can continue chat

/**
 * Tool call arguments from the AI response
 */
export interface ProposeSchemaToolArguments {
  intent_summary: string;
  action: SchemaAction;
}

/**
 * Tool call from the AI response
 */
export interface ToolCall {
  id: string;
  type: "function";
  function: {
    name: string;
    arguments: string; // JSON string
  };
}

/**
 * Tool definition for schema proposal
 */
export interface ToolDefinition {
  type: "function";
  function: {
    name: string;
    description: string;
    parameters: {
      type: "object";
      properties: Record<string, unknown>;
      required: string[];
      additionalProperties: boolean;
    };
    strict: boolean;
  };
}

/**
 * The schema proposal tool definition constant
 */
export const SCHEMA_PROPOSAL_TOOL: ToolDefinition = {
  type: "function",
  function: {
    name: "propose_schema_generation",
    description: `Call this when the user wants to generate, update, or modify the view schema. Do NOT call for general questions about the trace data.

Action selection guidance:
- Use "generate_new" when: no view exists yet, user explicitly requests a new/fresh view, or a complete redesign is needed
- Use "update_existing" when: modifying the current view (adding sections, removing sections, changing existing sections, renaming, reordering)

Examples:
- "Show me input and output" with no existing view → generate_new
- "Remove section 1" or "Delete the Overview" → update_existing
- "Add a metadata section" or "Include token usage" → update_existing
- "Create a new view from scratch" → generate_new
- "Change the title to X" → update_existing`,
    parameters: {
      type: "object",
      properties: {
        intent_summary: {
          type: "string",
          description:
            "A detailed summary of what the user wants to visualize or change. For updates, reference the specific sections by name or ID when possible (e.g., 'Remove the Overview section', 'Add a new section showing token usage after the Output section')",
        },
        action: {
          type: "string",
          enum: ["generate_new", "update_existing"],
          description:
            "The type of operation: 'generate_new' creates a fresh view from scratch, 'update_existing' modifies the current view structure",
        },
      },
      required: ["intent_summary", "action"],
      additionalProperties: false,
    },
    strict: true,
  },
};
