import React, { useCallback } from "react";
import { ClipboardPaste, Trash } from "lucide-react";

import { ChatLLMessage, LLM_MESSAGE_ROLE } from "@/types/llm";
import {
  getMessageContentImageSegments,
  getMessageContentTextSegments,
  stringifyMessageContent,
} from "@/lib/llm";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/ui/skeleton";
import { Card, CardContent } from "@/components/ui/card";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import CopyButton from "@/components/shared/CopyButton/CopyButton";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import { Button } from "@/components/ui/button";
import { useDeleteMessage, useUpdateChat } from "@/store/ChatStore";

type ChatMessageProps = {
  message: ChatLLMessage;
};

const ChatMessage: React.FC<ChatMessageProps> = ({ message }) => {
  const textSegments = getMessageContentTextSegments(message.content);
  const mergedText = textSegments.join("\n\n");
  const imageSegments = getMessageContentImageSegments(message.content);

  const hasText = mergedText.trim().length > 0;
  const hasImages = imageSegments.length > 0;
  const noContent = !hasText && !hasImages;
  const messageTextForCopy = stringifyMessageContent(message.content);
  const deleteMessage = useDeleteMessage();
  const updateChat = useUpdateChat();

  const onRemoveMessage = useCallback(() => {
    deleteMessage(message.id);
  }, [deleteMessage, message.id]);

  const onCopyToEditMessage = useCallback(() => {
    updateChat({ value: mergedText });
  }, [mergedText, updateChat]);

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
            <TooltipWrapper content="Delete a message">
              <Button
                variant="outline"
                size="icon-sm"
                onClick={onRemoveMessage}
                aria-label="Delete message"
              >
                <Trash />
              </Button>
            </TooltipWrapper>
            <TooltipWrapper content="Copy to input">
              <Button
                variant="outline"
                size="icon-sm"
                onClick={onCopyToEditMessage}
                aria-label="Copy message to input"
              >
                <ClipboardPaste />
              </Button>
            </TooltipWrapper>
            <CopyButton
              tooltipText="Copy message"
              text={messageTextForCopy}
              variant="outline"
            ></CopyButton>
          </div>
        )}

        <CardContent className="p-0">
          <div className="comet-body-s-accented inline-flex h-8 items-center justify-center font-normal text-light-slate">
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
            <>
              {hasText ? (
                <MarkdownPreview className="py-1">
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
        </CardContent>
      </Card>
    </div>
  );
};

export default ChatMessage;
