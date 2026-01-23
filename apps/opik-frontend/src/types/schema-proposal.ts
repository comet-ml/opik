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
    description:
      "Call this when the user wants to generate, update, or modify the view schema. Do NOT call for general questions about the trace data.",
    parameters: {
      type: "object",
      properties: {
        intent_summary: {
          type: "string",
          description:
            "What the user wants to visualize (e.g., 'Show input/output messages and token usage')",
        },
        action: {
          type: "string",
          enum: ["generate_new", "update_existing"],
          description: "Whether to create new schema or update current one",
        },
      },
      required: ["intent_summary", "action"],
      additionalProperties: false,
    },
    strict: true,
  },
};
