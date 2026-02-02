import React from "react";
import { AlertCircle, Check } from "lucide-react";

import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { IntakeMessage } from "@/types/agent-intake";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/ui/skeleton";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";

type IntakeChatMessageProps = {
  message: IntakeMessage;
};

const NO_ENDPOINTS_LABELS = {
  run_myself: "Run it myself",
  setup_endpoint: "Setup an endpoint",
};

const IntakeChatMessage: React.FC<IntakeChatMessageProps> = ({ message }) => {
  const isUser = message.role === LLM_MESSAGE_ROLE.user;
  const noContent = message.content === "";

  if (message.metadata?.type === "endpoint_selection") {
    return (
      <div className="mb-2 flex justify-end">
        <div className="flex items-center gap-2 rounded-full border border-primary/20 bg-primary/5 px-3 py-1.5">
          <div className="flex size-4 items-center justify-center rounded-full bg-primary">
            <Check className="size-2.5 text-primary-foreground" />
          </div>
          <span className="comet-body-s text-foreground">
            {message.metadata.endpointName}
          </span>
        </div>
      </div>
    );
  }

  if (message.metadata?.type === "no_endpoints_choice") {
    return (
      <div className="mb-2 flex justify-end">
        <div className="flex items-center gap-2 rounded-full border border-primary/20 bg-primary/5 px-3 py-1.5">
          <div className="flex size-4 items-center justify-center rounded-full bg-primary">
            <Check className="size-2.5 text-primary-foreground" />
          </div>
          <span className="comet-body-s text-foreground">
            {NO_ENDPOINTS_LABELS[message.metadata.choice]}
          </span>
        </div>
      </div>
    );
  }

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

export default IntakeChatMessage;
