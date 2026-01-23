import { useCallback } from "react";
import useStructuredCompletion from "@/api/playground/useStructuredCompletion";
import { customViewZodSchema, CustomViewSchema, ContextType } from "@/types/custom-view";
import { SchemaAction } from "@/types/schema-proposal";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

/**
 * Context for schema generation
 */
export interface SchemaGenerationContext {
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
  /** Current schema if updating */
  currentSchema: CustomViewSchema | null;
}

/**
 * Parameters for generating schema
 */
export interface GenerateSchemaParams {
  model: PROVIDER_MODEL_TYPE | string;
  context: SchemaGenerationContext;
}

/**
 * Hook parameters
 */
export interface UseSchemaGenerationAIParams {
  workspaceName: string;
}

/**
 * System prompt for schema generation
 */
const buildSystemPrompt = (context: SchemaGenerationContext): string => {
  const action = context.action === "generate_new" ? "Generate a new" : "Update the existing";
  const dataTypeLabel = context.dataType === "trace" ? "trace" : "thread";
  const dataTypeDescription = context.dataType === "trace" 
    ? "Trace data contains information about a single LLM execution, including input, output, metadata, usage, and feedback scores."
    : "Thread data contains information about a conversation thread, including first_message, last_message, number_of_messages, usage, and feedback scores.";
  
  return `You are an AI assistant that ${action.toLowerCase()} custom visualization schema for LLM ${dataTypeLabel} data.

User Intent: ${context.intentSummary}

${context.currentSchema ? `Current Schema: ${JSON.stringify(context.currentSchema, null, 2)}` : ""}

## Data Type: ${dataTypeLabel.toUpperCase()}
${dataTypeDescription}

## Available Widget Types:
- **text**: Plain text values (strings, general content)
- **number**: Numeric values (counts, metrics, scores)
- **boolean**: True/false values
- **code**: Code snippets, JSON objects, formatted data
- **link**: URLs that should be clickable
- **image**: Image URLs (jpg, png, gif, webp, svg)
- **video**: Video URLs (mp4, webm, mov)
- **audio**: Audio URLs (mp3, wav, m4a)
- **pdf**: PDF file URLs
- **file**: Generic file attachments

## Widget Sizes (REQUIRED):
- **small**: For compact data (numbers, booleans, short text ≤50 chars, links)
- **medium**: For standard content (text, images, audio)
- **large**: For rich content (code blocks, JSON, video, PDFs)
- **full**: For special cases requiring full width

## Path Format:
- Use dot notation: \`input.messages\`, \`output.result\`, \`first_message.content\`
- Use array indexing: \`messages[0].content\`, \`messages[1].content\`
- **IMPORTANT**: For arrays, list each item separately with its index

## Guidelines:
1. Identify the 4-8 most important fields based on user intent and data type
2. Choose appropriate widget type AND size for each field
3. **Sort widgets by size**: small → medium → large → full
4. Provide clear, descriptive labels
5. For arrays, create separate widgets for each item (up to 5 items)
6. Prioritize user-facing content over metadata

## Response Format:
Return a JSON object with:
- \`responseSummary\`: Brief explanation of what you've identified
- \`widgets\`: Array of widget configurations (sorted by size)

The ${dataTypeLabel} data is provided below:
{{data}}`;
};

/**
 * Schema Generation AI hook
 * Wraps useStructuredCompletion with context injection
 */
const useSchemaGenerationAI = ({
  workspaceName,
}: UseSchemaGenerationAIParams) => {
  const structuredCompletion = useStructuredCompletion({
    schema: customViewZodSchema,
    workspaceName,
  });

  const generateSchema = useCallback(
    async ({
      model,
      context,
    }: GenerateSchemaParams): Promise<CustomViewSchema | null> => {
      const systemPrompt = buildSystemPrompt(context);

      return await structuredCompletion.generate({
        model,
        userMessage: context.intentSummary,
        systemPrompt,
        context: {
          data: context.data,
        },
        configs: {
          temperature: 0.7,
        },
      });
    },
    [structuredCompletion],
  );

  return {
    generateSchema,
    isLoading: structuredCompletion.isLoading,
    error: structuredCompletion.error,
  };
};

export default useSchemaGenerationAI;
