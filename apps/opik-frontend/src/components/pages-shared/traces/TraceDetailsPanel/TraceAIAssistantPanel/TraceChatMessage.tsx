import React, { useCallback } from "react";
import { ClipboardPaste } from "lucide-react";

import { ChatLLMessage, LLM_MESSAGE_ROLE } from "@/types/llm";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/ui/skeleton";
import { Card, CardContent } from "@/components/ui/card";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import CopyButton from "@/components/shared/CopyButton/CopyButton";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import { Button } from "@/components/ui/button";

type TraceChatMessageProps = {
  message: ChatLLMessage;
  onCopyToInput?: (content: string) => void;
};

const TraceChatMessage: React.FC<TraceChatMessageProps> = ({
  message,
  onCopyToInput,
}) => {
  const noContent = message.content === "";

  const onCopyToEditMessage = useCallback(() => {
    onCopyToInput?.(message.content);
  }, [message.content, onCopyToInput]);

  return (
    <div
      key={message.id}
      className={cn(
        "mb-2 flex",
        message.role === LLM_MESSAGE_ROLE.user
          ? "justify-end"
          : "justify-start",
      )}
    >
      <Card
        className={cn(
          "relative max-w-[90%] border py-2 px-3 min-w-[20%] group",
          noContent && "w-4/5",
        )}
      >
        {!message.isLoading && (
          <div className="absolute right-2 top-2 hidden gap-1 group-hover:flex">
            <TooltipWrapper content="Copy to input">
              <Button
                variant="outline"
                size="icon-sm"
                onClick={onCopyToEditMessage}
              >
                <ClipboardPaste />
              </Button>
            </TooltipWrapper>
            <CopyButton
              tooltipText="Copy message"
              text={message.content}
              variant="outline"
            ></CopyButton>
          </div>
        )}

        <CardContent className="p-0">
          <div className="comet-body-s-accented text-light-slate inline-flex h-8 items-center justify-center font-normal">
            {LLM_MESSAGE_ROLE_NAME_MAP[message.role] || message.role}
          </div>
          {noContent ? (
            <div className="flex w-full flex-wrap gap-2 overflow-hidden">
              <Skeleton className="inline-block h-2 w-1/4" />
              <Skeleton className="inline-block h-2 w-2/3" />
              <Skeleton className="inline-block h-2 w-3/4" />
              <Skeleton className="inline-block h-2 w-1/4" />
            </div>
          ) : (
            <MarkdownPreview className="py-1">
              {message.content}
            </MarkdownPreview>
          )}
        </CardContent>
      </Card>
    </div>
  );
};

export default TraceChatMessage;
