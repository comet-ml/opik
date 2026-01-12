import React from "react";
import { AlertCircle, Loader2, CheckCircle2 } from "lucide-react";

import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { TraceAnalyzerLLMMessage, MESSAGE_TYPE } from "@/types/ai-assistant";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/ui/skeleton";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";

type TraceChatMessageProps = {
  message: TraceAnalyzerLLMMessage;
};

const TraceChatMessage: React.FC<TraceChatMessageProps> = ({ message }) => {
  const isUser = message.role === LLM_MESSAGE_ROLE.user;
  const isToolCall = message.messageType === MESSAGE_TYPE.tool_call;

  // Tool call messages have their own rendering
  if (isToolCall && message.toolCalls) {
    return (
      <div className="mb-2 flex justify-start">
        <div className="relative min-w-[20%] max-w-[90%] rounded-t-xl rounded-br-xl bg-muted/30 px-4 py-2">
          <div className="flex flex-col gap-1.5">
            {message.toolCalls.map((toolCall) => (
              <div
                key={toolCall.id}
                className="flex items-center gap-2 text-muted-foreground"
              >
                {toolCall.completed ? (
                  <CheckCircle2 className="size-3 text-green-600" />
                ) : (
                  <Loader2 className="size-3 animate-spin" />
                )}
                <span className="comet-body-xs">{toolCall.display_name}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  const noContent = message.content === "";

  return (
    <div
      key={message.id}
      className={cn("mb-2 flex", isUser ? "justify-end" : "justify-start")}
    >
      <div
        className={cn(
          "relative min-w-[20%] max-w-[90%] px-4 py-2",
          isUser
            ? "rounded-t-xl rounded-bl-xl bg-[var(--message-input-background)]"
            : "rounded-t-xl rounded-br-xl bg-primary-foreground",
          message.isError && "bg-destructive/10 border border-destructive",
          noContent && "w-4/5",
        )}
      >
        {message.isError && (
          <div className="mb-1 flex items-center gap-1 text-destructive">
            <AlertCircle className="size-3" />
            <span className="comet-body-s-accented">Error</span>
          </div>
        )}
        {noContent ? (
          <div className="flex w-full flex-wrap gap-2 overflow-hidden">
            <Skeleton className="inline-block h-2 w-1/4" />
            <Skeleton className="inline-block h-2 w-2/3" />
            <Skeleton className="inline-block h-2 w-3/4" />
            <Skeleton className="inline-block h-2 w-1/4" />
          </div>
        ) : (
          <MarkdownPreview
            className={cn(message.isError && "text-destructive")}
          >
            {message.content}
          </MarkdownPreview>
        )}
      </div>
    </div>
  );
};

export default TraceChatMessage;
