import React from "react";
import { AlertCircle } from "lucide-react";

import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { TraceAnalyzerLLMMessage } from "@/types/ai-assistant";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/ui/skeleton";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";

type TraceChatMessageProps = {
  message: TraceAnalyzerLLMMessage;
};

const TraceChatMessage: React.FC<TraceChatMessageProps> = ({ message }) => {
  const noContent = message.content === "";
  const isUser = message.role === LLM_MESSAGE_ROLE.user;

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
        {message.status && (
          <div className="comet-body-xs text-muted-slate">{message.status}</div>
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
