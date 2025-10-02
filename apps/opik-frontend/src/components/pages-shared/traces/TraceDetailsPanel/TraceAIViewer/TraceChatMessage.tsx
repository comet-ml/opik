import React from "react";
import { AlertCircle } from "lucide-react";

import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { TraceAnalyzerLLMMessage } from "@/types/ai-assistant";
import {
  getMessageContentImageSegments,
  getMessageContentTextSegments,
} from "@/lib/llm";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/ui/skeleton";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";

type TraceChatMessageProps = {
  message: TraceAnalyzerLLMMessage;
};

const TraceChatMessage: React.FC<TraceChatMessageProps> = ({ message }) => {
  const textSegments = getMessageContentTextSegments(message.content);
  const mergedText = textSegments.join("\n\n");
  const imageSegments = getMessageContentImageSegments(message.content);

  const hasText = mergedText.trim().length > 0;
  const hasImages = imageSegments.length > 0;
  const noContent = !hasText && !hasImages;
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
          <>
            {hasText ? (
              <MarkdownPreview
                className={cn(message.isError && "text-destructive")}
              >
                {mergedText}
              </MarkdownPreview>
            ) : null}
            {hasImages ? (
              <div className="mt-2 flex flex-wrap gap-2">
                {imageSegments.map((segment, index) => (
                  <img
                    key={`${message.id}-image-${index}`}
                    src={segment.image_url.url}
                    alt={`Message image ${index + 1}`}
                    className="max-h-40 rounded-md border border-border object-contain"
                  />
                ))}
              </div>
            ) : null}
          </>
        )}
      </div>
    </div>
  );
};

export default TraceChatMessage;
