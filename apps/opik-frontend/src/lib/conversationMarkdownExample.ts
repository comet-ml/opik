import { convertConversationToMarkdown } from "./conversationMarkdown";

/**
 * Example usage of the convertConversationToMarkdown function
 */
export const exampleUsage = () => {
  // Example conversation data
  const conversationData = {
    model: "openai/gpt-4o-mini",
    messages: [
      {
        role: "system" as const,
        content:
          "\nYou are a helpful AI system for answering questions that can be answered\nwith any of the available tools.\n",
      },
      {
        role: "user" as const,
        content: "What is devrob?",
      },
      {
        role: "assistant" as const,
        tool_calls: [
          {
            id: "call_WOVG7NKttOdJPk3Y7BkqV8B9",
            type: "function" as const,
            function: {
              name: "ez-mcp-server_search_wikipedia",
              arguments: '{"query":"devrob"}',
            },
          },
        ],
        content: "",
      },
      {
        role: "tool" as const,
        tool_call_id: "call_WOVG7NKttOdJPk3Y7BkqV8B9",
        content:
          '["Developmental robotics: Developmental robotics (DevRob), sometimes called epigenetic robotics, is a scientific field which aims at studying the developmental mechanisms, architectures"]',
      },
      {
        role: "assistant" as const,
        content:
          "DevRob, or Developmental Robotics, is a scientific field that focuses on studying the developmental mechanisms and architectures in robotics.",
      },
    ],
    tools: [
      {
        type: "function" as const,
        function: {
          name: "ez-mcp-server_search_wikipedia",
          description: "Search Wikipedia for information about a given query.",
          parameters: {
            type: "object",
            properties: {
              query: {
                type: "string",
                description: "The search term to look up on Wikipedia",
              },
            },
            required: ["query"],
          },
        },
      },
    ],
    kwargs: {
      temperature: 0.2,
    },
  };

  // Basic conversion
  const basicMarkdown = convertConversationToMarkdown(conversationData);
  console.log("Basic conversion:");
  console.log(basicMarkdown);

  // Conversion with tools included
  const withToolsMarkdown = convertConversationToMarkdown(conversationData, {
    includeTools: true,
    includeKwargs: true,
  });
  console.log("\nWith tools and configuration:");
  console.log(withToolsMarkdown);

  return {
    basic: basicMarkdown,
    withTools: withToolsMarkdown,
  };
};
