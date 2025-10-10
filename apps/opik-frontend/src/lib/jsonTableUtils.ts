/**
 * Check if content should be rendered as a JSON table
 */
export const shouldRenderAsJsonTable = (content: string): boolean => {
  try {
    const parsed = JSON.parse(content);
    return typeof parsed === "object" && parsed !== null;
  } catch {
    return false;
  }
};

/**
 * Parse JSON content for table rendering
 */
export const parseJsonForTable = (content: string): unknown | null => {
  try {
    const parsed = JSON.parse(content);
    if (typeof parsed === "object" && parsed !== null) {
      return parsed;
    }
  } catch {
    // Ignore parsing errors
  }
  return null;
};

/**
 * Format structured JSON data in a readable way
 */
export const formatStructuredData = (data: unknown): string => {
  if (
    typeof data === "object" &&
    data !== null &&
    "message_list" in (data as Record<string, unknown>) &&
    Array.isArray((data as Record<string, unknown>).message_list)
  ) {
    // Handle message_list + examples format
    const parts: string[] = [];
    const dataObj = data as Record<string, unknown>;

    // Format the message_list as a nested conversation
    if ((dataObj.message_list as unknown[]).length > 0) {
      parts.push("**Message Template:**");
      (dataObj.message_list as unknown[]).forEach((msg: unknown) => {
        if (
          typeof msg === "object" &&
          msg !== null &&
          "role" in (msg as Record<string, unknown>) &&
          "content" in (msg as Record<string, unknown>)
        ) {
          const msgObj = msg as Record<string, unknown>;
          const role = msgObj.role as string;
          const content = msgObj.content as string;
          const roleHeader = role.charAt(0).toUpperCase() + role.slice(1);
          parts.push(`  **${roleHeader}**: ${content}`);
        }
      });
    }

    // Format examples if present
    if (
      "examples" in dataObj &&
      Array.isArray(dataObj.examples) &&
      (dataObj.examples as unknown[]).length > 0
    ) {
      parts.push("\n**Examples:**");
      (dataObj.examples as unknown[]).forEach(
        (example: unknown, index: number) => {
          if (
            typeof example === "object" &&
            example !== null &&
            "question" in (example as Record<string, unknown>) &&
            "answer" in (example as Record<string, unknown>)
          ) {
            const exampleObj = example as Record<string, unknown>;
            const question = exampleObj.question as string;
            const answer = exampleObj.answer as string;
            parts.push(`  ${index + 1}. **Q:** ${question}`);
            parts.push(`     **A:** ${answer}`);
          }
        },
      );
    }

    return parts.join("\n");
  }

  // Fallback to JSON string for other structured data
  return JSON.stringify(data, null, 2);
};
