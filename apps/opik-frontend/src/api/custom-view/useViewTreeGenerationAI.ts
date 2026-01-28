import { useCallback } from "react";
import useStreamingTreeCompletion from "./useStreamingTreeCompletion";
import type { ViewTree, ViewPatch, SourceData } from "@/lib/data-view";
import { generatePrompt } from "@/lib/data-view";
import { customViewCatalog } from "@/components/pages/CustomViewDemoPage/data-view-widgets";
import { SchemaAction } from "@/types/schema-proposal";
import { ContextType } from "@/types/custom-view";
import { PROVIDER_MODEL_TYPE, LLMPromptConfigsType } from "@/types/providers";

/**
 * Context for ViewTree generation
 */
export interface ViewTreeGenerationContext {
  /** User's intent from the tool call */
  intentSummary: string;
  /** Action type */
  action: SchemaAction;
  /** Full data (trace or thread) */
  data: unknown;
  /** Type of data being visualized */
  dataType: ContextType;
  /** Selected model */
  model: string;
  /** Current tree if updating */
  currentTree: ViewTree | null;
}

/**
 * Parameters for generating ViewTree
 */
export interface GenerateViewTreeParams {
  model: PROVIDER_MODEL_TYPE | string;
  context: ViewTreeGenerationContext;
  configs?: Partial<LLMPromptConfigsType>;
  /** Optional custom system prompt template (null = use default) */
  customSystemPrompt?: string | null;
  /** Callback fired for each patch during streaming */
  onPatch?: (patch: ViewPatch, tree: ViewTree) => void;
  /** Callback fired when streaming completes */
  onComplete?: (tree: ViewTree) => void;
}

/**
 * Hook parameters
 */
export interface UseViewTreeGenerationAIParams {
  workspaceName: string;
}

// ============================================================================
// JSONL FORMAT INSTRUCTIONS FOR PROMPT
// ============================================================================

const JSONL_FORMAT_INSTRUCTIONS = `
## OUTPUT FORMAT (JSONL - Newline-Delimited JSON)

Output JSONL (newline-delimited JSON). Each line is a ViewPatch object.
Do NOT wrap in markdown code blocks. Output raw JSONL only.

**ViewPatch Format:**
Each line must be a valid JSON object with these fields:
- "op": Operation type - "add", "replace", or "remove"
- "path": JSON Pointer path (e.g., "/version", "/nodes/my-id")
- "value": The value to set (required for "add" and "replace")

**Required Build Order:**
1. First, add version: {"op":"add","path":"/version","value":1}
2. Then, add root: {"op":"add","path":"/root","value":"container-root"}
3. Then, add empty nodes: {"op":"add","path":"/nodes","value":{}}
4. Then, add individual nodes one by one

**Example Output (with JSON input data - use Code widget):**
{"op":"add","path":"/version","value":1}
{"op":"add","path":"/root","value":"root-container"}
{"op":"add","path":"/nodes","value":{}}
{"op":"add","path":"/nodes/root-container","value":{"id":"root-container","type":"Container","props":{"layout":"stack","gap":"md"},"children":["input-code","trace-context","output-block"],"parentKey":null}}
{"op":"add","path":"/nodes/input-code","value":{"id":"input-code","type":"Code","props":{"content":{"path":"/input"},"label":"Input","language":"json"},"children":null,"parentKey":"root-container"}}
{"op":"add","path":"/nodes/trace-context","value":{"id":"trace-context","type":"Level1Container","props":{"title":"Trace context"},"children":["row-name","row-duration","tool-section"],"parentKey":"root-container"}}
{"op":"add","path":"/nodes/row-name","value":{"id":"row-name","type":"InlineRow","props":{},"children":["label-name","text-name"],"parentKey":"trace-context"}}
{"op":"add","path":"/nodes/label-name","value":{"id":"label-name","type":"Label","props":{"text":"Name"},"children":null,"parentKey":"row-name"}}
{"op":"add","path":"/nodes/text-name","value":{"id":"text-name","type":"Text","props":{"value":{"path":"/name"}},"children":null,"parentKey":"row-name"}}
{"op":"add","path":"/nodes/row-duration","value":{"id":"row-duration","type":"InlineRow","props":{},"children":["label-duration","num-duration"],"parentKey":"trace-context"}}
{"op":"add","path":"/nodes/label-duration","value":{"id":"label-duration","type":"Label","props":{"text":"Duration (ms)"},"children":null,"parentKey":"row-duration"}}
{"op":"add","path":"/nodes/num-duration","value":{"id":"num-duration","type":"Number","props":{"value":{"path":"/duration"}},"children":null,"parentKey":"row-duration"}}
{"op":"add","path":"/nodes/tool-section","value":{"id":"tool-section","type":"Level2Container","props":{"summary":"Tool response","icon":"tool","defaultOpen":false},"children":["tool-response-code"],"parentKey":"trace-context"}}
{"op":"add","path":"/nodes/tool-response-code","value":{"id":"tool-response-code","type":"Code","props":{"content":{"path":"/tool_response"},"language":"json"},"children":null,"parentKey":"tool-section"}}
{"op":"add","path":"/nodes/output-block","value":{"id":"output-block","type":"TextBlock","props":{"content":{"path":"/output/content"},"label":"Output"},"children":null,"parentKey":"root-container"}}

**Critical Rules:**
1. ONE patch per line - no multi-line JSON
2. Each line must be valid JSON - check your syntax
3. Build incrementally: version -> root -> nodes -> individual nodes
4. Use {"path": "/json/pointer/path"} for dynamic data binding to source data
5. Static values (like section titles) should be literal strings
6. Node IDs must be unique strings (use descriptive names like "section-input", "text-output")
7. Container nodes (Container, Level1Container, Level2Container, InlineRow) must have "children" array with child node IDs
8. Child nodes must have "parentKey" set to their parent's ID
9. Every node must include: id, type, props, children (array or null), parentKey (string or null)
10. **CRITICAL:** Use Code widget (not TextBlock) for JSON/object data - TextBlock is for text/markdown only
11. **CRITICAL:** Key-value pairs MUST use InlineRow with Label + Text/Number - never use Label alone
12. **CRITICAL:** Tool calls, tool responses must use Level2Container with icon="tool" and dynamic summary
13. **CRITICAL:** Level1Container CANNOT contain Level1Container - use Level2Container for nested items
`;

const UPDATE_FORMAT_INSTRUCTIONS = `
## UPDATE MODE (Modifying an existing tree)

IMPORTANT: The current tree is already loaded. Output ONLY the patches needed for the requested change.

**DO NOT recreate these - they already exist:**
- version, root, nodes object

**When ADDING a new node:**
You need TWO patches - add the node AND update the parent's children array:
1. {"op":"add","path":"/nodes/{nodeId}","value":{...}}
2. {"op":"add","path":"/nodes/{parentId}/children/-","value":"{nodeId}"}

The "/-" means "append to array".

**Example - Adding a new section to root:**
If root is "root-container":
{"op":"add","path":"/nodes/new-section","value":{"id":"new-section","type":"Level1Container","props":{"title":"Messages"},"children":[],"parentKey":"root-container"}}
{"op":"add","path":"/nodes/root-container/children/-","value":"new-section"}

**Example - Adding a child widget inside a container:**
{"op":"add","path":"/nodes/text-new","value":{"id":"text-new","type":"TextBlock","props":{"content":{"path":"/data"},"label":"Data"},"children":null,"parentKey":"details-section"}}
{"op":"add","path":"/nodes/details-section/children/-","value":"text-new"}

**Example - Adding traces inside a conversation section (use Level2Container, NOT Level1Container):**
{"op":"add","path":"/nodes/trace-1","value":{"id":"trace-1","type":"Level2Container","props":{"summary":"Trace 1","icon":"generation","defaultOpen":false},"children":["trace-1-content"],"parentKey":"conversation-section"}}
{"op":"add","path":"/nodes/conversation-section/children/-","value":"trace-1"}

**When REMOVING a node:**
{"op":"remove","path":"/nodes/{nodeId}"}
{"op":"remove","path":"/nodes/{parentId}/children/{index}"}

**When MODIFYING a node's props:**
{"op":"replace","path":"/nodes/{nodeId}/props/{propName}","value":"new value"}

DO NOT output patches for nodes that don't change.
`;

// ============================================================================
// SCHEMA GENERATION RULES
// ============================================================================

const SCHEMA_GENERATION_RULES = `
## View Structure Rules

### CRITICAL: Root Node Structure
- The root node MUST be a Container (type: "Container") that holds all other nodes
- NEVER use TextBlock, Text, Label, or other leaf nodes as the root
- Only Container, Level1Container, Level2Container, and InlineRow can have children
- TextBlock, Text, Label, Code, Image, Video, Audio, etc. are leaf nodes and CANNOT have children (children must be null)

### Required Elements
- Every view MUST have exactly one TextBlock with role="input" - but ONLY if the input data is TEXT/markdown
- Every view MUST have exactly one TextBlock with role="output" - but ONLY if the output data is TEXT/markdown
- **CRITICAL:** If input or output is JSON/object data, use Code widget with language="json" instead of TextBlock
- Input/Output blocks should be direct children of the root Container

### CRITICAL: Data Type Detection and Widget Selection
**Always check the data type before choosing a widget:**
1. **For STRING/TEXT/MARKDOWN content:** Use TextBlock (for blocks) or Text (for inline)
2. **For JSON/OBJECT/ARRAY content:** Use Code widget with language="json"
3. **NEVER use TextBlock for JSON data** - it will display "[object Object]"

Example - If /input contains {"messages": [...]}:
- WRONG: TextBlock with content: {"path": "/input"}
- CORRECT: Code with content: {"path": "/input"}, language: "json", label: "Input"

### CRITICAL: Key-Value Pairs Layout
**NEVER use Label widgets alone.** For key-value pairs (like "Name", "Duration", "Model", etc.):
- ALWAYS wrap in InlineRow container with Label + value widget
- Use Label for the key, Text/Number for the value

Example - Displaying "Duration: 5644.77ms":
- WRONG: Just a Label widget with text "Duration (ms)"
- CORRECT:
  1. Create InlineRow container
  2. Add Label child with text "Duration (ms)"
  3. Add Number child with value bound to the duration path

Pattern for key-value in InlineRow:
{"id":"row-duration","type":"InlineRow","props":{},"children":["label-duration","value-duration"],"parentKey":"parent-id"}
{"id":"label-duration","type":"Label","props":{"text":"Duration (ms)"},"children":null,"parentKey":"row-duration"}
{"id":"value-duration","type":"Number","props":{"value":{"path":"/duration"}},"children":null,"parentKey":"row-duration"}

### Container Rules
- Level1Container: TOP-LEVEL semantic sections only (metadata, trace context, conversation)
  - **CANNOT contain other Level1Containers** - this is strictly forbidden
  - Can contain: Level2Container, InlineRow, blocks, primitives
  - Has collapsible title with border
  - Use for: "Trace context", "Metadata", "Conversation", etc.

- Level2Container: for NESTED collapsible items inside Level1Container
  - **MUST be used for items inside Level1Container** (not another Level1Container)
  - MUST have "summary" prop (dynamic name from data, e.g., "Trace 1", tool name)
  - MUST have "icon" prop: "tool" for tool calls, "retrieval" for data fetching, "generation" for AI outputs
  - Use for: Individual traces, Tools, Tool response, Function calls, Raw payloads
  - Default collapsed (defaultOpen: false)
  - Inside: use Code widget for JSON, TextBlock for text, ChatMessage for messages

**NESTING HIERARCHY (follow strictly):**
Container (root)
  └── Level1Container (top-level sections)
        └── Level2Container (nested items - traces, tools, etc.)
              └── blocks/primitives (Code, TextBlock, ChatMessage, InlineRow)

**WRONG:** Level1Container → Level1Container (NEVER do this!)
**CORRECT:** Level1Container → Level2Container → content

### CRITICAL: Tool Calls and Trace Data Structure
For tool-related data (Tools, Tool response, Assistant tool, Function calls):
1. Create Level2Container with:
   - summary: Bind to tool/function name from data (dynamic)
   - icon: "tool"
   - status: "success" or "error" based on result
2. Inside Level2Container:
   - For JSON arguments/response: Use Code widget
   - For text content: Use TextBlock

Example structure for a tool call:
{"id":"tool-container","type":"Level2Container","props":{"summary":{"path":"/tools/0/name"},"icon":"tool","status":"success"},"children":["tool-input","tool-output"],"parentKey":"parent"}
{"id":"tool-input","type":"Code","props":{"content":{"path":"/tools/0/input"},"label":"Input","language":"json"},"children":null,"parentKey":"tool-container"}
{"id":"tool-output","type":"Code","props":{"content":{"path":"/tools/0/output"},"label":"Output","language":"json"},"children":null,"parentKey":"tool-container"}

### Layout Rules
- InlineRow: for key-value pairs (Label + Text/Number)
- Block components (TextBlock, Code, Image) get their own row
- Inline primitives (Label, Text, Number, Tag) should be in InlineRow

### Background Content
- System prompts, tool calls, spans, logs → use Level2Container with defaultOpen: false
- User/assistant content → foreground, never collapsible
`;

/**
 * Build system prompt for ViewTree generation with JSONL format
 * Supports custom template with variable substitution
 */
const buildSystemPrompt = (
  context: ViewTreeGenerationContext,
  customTemplate?: string | null,
): string => {
  const action =
    context.action === "generate_new"
      ? "creating a new"
      : "updating the existing";
  const dataTypeLabel = context.dataType === "trace" ? "trace" : "thread";

  // Use the catalog's generatePrompt for component documentation and data paths
  const catalogPrompt = generatePrompt(customViewCatalog, {
    includeDataPaths: true,
    sourceData: context.data as SourceData,
    maxDataDepth: 4,
    existingTree: context.currentTree ?? undefined,
  });

  // Extract widget catalog and data paths from the generated prompt
  // The catalog prompt contains both, so we use it as widget_catalog
  const widgetCatalog = catalogPrompt;

  // Build data paths section
  const dataPaths = `Available data paths are documented in the widget catalog above.`;

  // Build format instructions
  const formatInstructions = context.currentTree
    ? `${JSONL_FORMAT_INSTRUCTIONS}\n\n${UPDATE_FORMAT_INSTRUCTIONS}`
    : JSONL_FORMAT_INSTRUCTIONS;

  // Build current tree section
  const currentTreeSection = context.currentTree
    ? `**Current Tree:** The user wants to modify the existing view.\n\`\`\`json\n${JSON.stringify(
        context.currentTree,
        null,
        2,
      )}\n\`\`\``
    : "";

  // If custom template provided, substitute variables
  if (customTemplate) {
    return customTemplate
      .replace(/\{\{intent_summary\}\}/g, context.intentSummary)
      .replace(/\{\{action\}\}/g, action)
      .replace(/\{\{data_type\}\}/g, dataTypeLabel)
      .replace(/\{\{widget_catalog\}\}/g, widgetCatalog)
      .replace(/\{\{data_paths\}\}/g, dataPaths)
      .replace(/\{\{format_instructions\}\}/g, formatInstructions)
      .replace(/\{\{current_tree\}\}/g, currentTreeSection);
  }

  // Default prompt construction
  const dataTypeDescription =
    context.dataType === "trace"
      ? `Trace data contains information about a single LLM execution:
- /input: The input data (CHECK TYPE: if object/JSON use Code widget, if string use TextBlock)
- /output: The output data (CHECK TYPE: if object/JSON use Code widget, if string use TextBlock)
- /name, /duration, /model: Metadata fields (use InlineRow + Label + Text/Number)
- /tools, /tool_response: Tool-related data (use Level2Container with icon="tool", Code widget inside)
- /metadata, /usage: Additional info (use InlineRow for key-value pairs)
- /feedback_scores: Scores/ratings (use Number widgets)`
      : `Thread data contains a conversation with:
- Thread metadata: id, duration, number_of_messages, usage, etc.
- /traces array: All messages/traces in chronological order

**CORRECT structure for threads:**
Level1Container "Conversation" (or "Thread")
  └── Level2Container for each trace (summary: "Trace 1" or trace name, icon: "generation")
        └── ChatMessage or TextBlock/Code for content

**WRONG:** Level1Container containing Level1Container for each trace
**CORRECT:** Level1Container containing Level2Container for each trace

- /traces/N/input: user message (ChatMessage role="user" or Code if JSON)
- /traces/N/output: assistant response (ChatMessage role="assistant" or Code if JSON)
- For tool calls within traces: nested Level2Container with icon="tool"`;

  // Add context-specific instructions
  const contextSection = `## Context
You are ${action} custom visualization for LLM ${dataTypeLabel} data.

**User Intent:** ${context.intentSummary}

${currentTreeSection}

## Data Type: ${dataTypeLabel.toUpperCase()}
${dataTypeDescription}

## Best Practices for ${dataTypeLabel === "trace" ? "Traces" : "Threads"}
- Organize related fields into Section widgets with clear titles
- Use appropriate widgets for different data types:
  - Text/Code for strings and formatted content
  - Image/Video/Audio for media URLs
  - KeyValue for simple label-value pairs
- For trace conversations: display messages in order with proper labels
- For thread data: highlight key metrics (message count, usage)
- Bind dynamic data using { "path": "/json/pointer/path" } syntax (e.g., { "path": "/model" }, { "path": "/tools/0/name" })
- Static values (like section titles) should be literal strings: "My Title"`;

  return `${catalogPrompt}\n\n${SCHEMA_GENERATION_RULES}\n\n${formatInstructions}\n\n${contextSection}`;
};

/**
 * ViewTree Generation AI hook
 * Uses JSONL streaming for progressive tree building.
 * Works with ANY model (OpenAI, Anthropic, Gemini, etc.)
 */
const useViewTreeGenerationAI = ({
  workspaceName,
}: UseViewTreeGenerationAIParams) => {
  // Use streaming completion hook
  const streamingCompletion = useStreamingTreeCompletion({
    workspaceName,
  });

  const generateViewTree = useCallback(
    async ({
      model,
      context,
      configs,
      customSystemPrompt,
      onPatch,
      onComplete,
    }: GenerateViewTreeParams): Promise<ViewTree | null> => {
      const systemPrompt = buildSystemPrompt(context, customSystemPrompt);

      const result = await streamingCompletion.generate({
        model: model as string,
        userMessage: context.intentSummary,
        systemPrompt,
        configs,
        initialTree: context.currentTree ?? undefined,
        onPatch: (patch, tree) => {
          onPatch?.(patch, tree);
        },
        onComplete: (tree) => {
          onComplete?.(tree);
        },
        onError: (error) => {
          console.error("ViewTree generation error:", error);
        },
      });

      return result;
    },
    [streamingCompletion],
  );

  return {
    generateViewTree,
    isLoading: streamingCompletion.isStreaming,
    isStreaming: streamingCompletion.isStreaming,
    error: streamingCompletion.error,
    patchCount: streamingCompletion.patchCount,
    currentTree: streamingCompletion.tree,
    abort: streamingCompletion.abort,
    reset: streamingCompletion.reset,
  };
};

export default useViewTreeGenerationAI;
