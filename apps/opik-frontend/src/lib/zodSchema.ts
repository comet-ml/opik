import { z } from "zod";
import { zodToJsonSchema } from "zod-to-json-schema";

export interface OpenAIJsonSchema {
  type: "json_schema";
  json_schema: {
    name: string;
    strict: boolean;
    schema: Record<string, unknown>;
  };
}

export const convertZodToOpenAIFormat = <T extends z.ZodSchema>(
  schema: T,
  name: string = "response_schema"
): OpenAIJsonSchema => {
  const jsonSchema = zodToJsonSchema(schema, {
    name,
    $refStrategy: "none", // Inline all refs for OpenAI compatibility
  });

  // Extract the actual schema (remove $schema and definitions wrapper)
  const actualSchema = jsonSchema.definitions?.[name] ?? jsonSchema;

  return {
    type: "json_schema",
    json_schema: {
      name,
      strict: true,
      schema: actualSchema as Record<string, unknown>,
    },
  };
};
