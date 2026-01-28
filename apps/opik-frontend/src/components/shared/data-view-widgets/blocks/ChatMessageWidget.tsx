import React from "react";
import { z } from "zod";
import { cn } from "@/lib/utils";
import { DynamicString } from "@/lib/data-view";
import { MarkdownPreview } from "@/components/shared/MarkdownPreview/MarkdownPreview";

// ============================================================================
// TYPES
// ============================================================================

export type ChatMessageRole = "user" | "assistant";

export interface ChatMessageWidgetProps {
  content: string;
  role: ChatMessageRole;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const chatMessageWidgetConfig = {
  type: "ChatMessage" as const,
  category: "block" as const,
  schema: z.object({
    content: DynamicString.describe("Message content to display"),
    role: z
      .enum(["user", "assistant"])
      .describe("Message role: 'user' or 'assistant'"),
  }),
  description:
    "Chat message bubble for conversation display. User messages are right-aligned purple, assistant messages are left-aligned white.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * ChatMessageWidget - Conversation message bubble
 *
 * Figma reference: Thread overview conversation messages
 * Styles:
 * - User: Right-aligned, purple background (#E8DEF8), rounded except bottom-right
 * - Assistant: Left-aligned, white background with border, rounded except bottom-left
 * - No label/title above the bubble
 * - Renders content as markdown
 */
export const ChatMessageWidget: React.FC<ChatMessageWidgetProps> = ({
  content,
  role,
}) => {
  if (!content) return null;

  const isUser = role === "user";

  // Container alignment
  const containerClasses = cn(
    "flex w-full",
    isUser ? "justify-end" : "justify-start",
  );

  // Bubble styling
  const bubbleClasses = cn(
    "max-w-[85%] px-4 py-3",
    isUser
      ? "rounded-tl-xl rounded-tr-xl rounded-bl-xl bg-[#E8DEF8]" // User: rounded except bottom-right
      : "rounded-tl-xl rounded-tr-xl rounded-br-xl border border-border bg-white", // Assistant: rounded except bottom-left
  );

  return (
    <div className={containerClasses}>
      <div className={bubbleClasses}>
        <MarkdownPreview className="comet-body-s text-foreground">
          {content}
        </MarkdownPreview>
      </div>
    </div>
  );
};

export default ChatMessageWidget;
