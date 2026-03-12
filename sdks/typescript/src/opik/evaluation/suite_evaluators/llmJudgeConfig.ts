export interface LLMJudgeConfig {
  [key: string]: unknown;
  version: string;
  name: string;
  model: {
    name: string;
    temperature?: number;
    seed?: number;
    customParameters?: Record<string, unknown>;
  };
  messages: Array<{
    role: "SYSTEM" | "USER" | "AI" | "TOOL_EXECUTION_RESULT" | "CUSTOM";
    content: string;
  }>;
  variables: Record<string, string>;
  schema: Array<{
    name: string;
    type: "BOOLEAN" | "INTEGER" | "DOUBLE";
    description: string;
  }>;
}
