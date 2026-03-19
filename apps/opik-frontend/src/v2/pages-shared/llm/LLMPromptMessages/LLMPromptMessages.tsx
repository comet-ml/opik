import React, { useCallback, useRef } from "react";
import { arrayMove, verticalListSortingStrategy } from "@dnd-kit/sortable";
import keyBy from "lodash/keyBy";
import { Plus } from "lucide-react";
import type { DragEndEvent } from "@dnd-kit/core/dist/types";
import {
  closestCenter,
  DndContext,
  MouseSensor,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import { restrictToVerticalAxis } from "@dnd-kit/modifiers";
import { SortableContext } from "@dnd-kit/sortable";

import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import LLMPromptMessage, {
  LLMPromptMessageHandle,
} from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessage";
import { Button } from "@/ui/button";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { DropdownOption } from "@/types/shared";
import { ImprovePromptConfig } from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessageActions";
import { JsonObject } from "@/types/shared";

interface MessageValidationError {
  content?: {
    message: string;
  };
}

interface LLMPromptMessagesProps {
  messages: LLMMessage[];
  validationErrors?: MessageValidationError[];
  possibleTypes?: DropdownOption<LLM_MESSAGE_ROLE>[];
  hidePromptActions?: boolean;
  onChange: (messages: LLMMessage[]) => void;
  onAddMessage: () => void;
  promptVariables?: string[];
  disableMedia?: boolean;
  improvePromptConfig?: ImprovePromptConfig;
  hideAddButton?: boolean;
  jsonTreeData?: JsonObject | null;
}

const LLMPromptMessages = ({
  messages,
  validationErrors,
  possibleTypes,
  hidePromptActions = true,
  onChange,
  onAddMessage,
  promptVariables = [],
  disableMedia = false,
  improvePromptConfig,
  hideAddButton = false,
  jsonTreeData,
}: LLMPromptMessagesProps) => {
  const lastFocusedMessageIdRef = useRef<string | null>(null);
  const messageRefsMap = useRef<Map<string, LLMPromptMessageHandle>>(new Map());
  const listRef = useRef<HTMLDivElement>(null);

  const sensors = useSensors(
    useSensor(MouseSensor, {
      activationConstraint: {
        distance: 2,
      },
    }),
  );

  const handleDuplicateMessage = useCallback(
    (message: Partial<LLMMessage> = {}, position: number) => {
      const newMessage = generateDefaultLLMPromptMessage(message);
      const newMessages = [...messages];

      newMessages.splice(position, 0, newMessage);

      onChange(newMessages);
      requestAnimationFrame(() => {
        listRef.current?.children[position]?.scrollIntoView({
          behavior: "smooth",
          block: "nearest",
        });
      });
    },
    [onChange, messages],
  );

  const handleRemoveMessage = useCallback(
    (messageId: string) => {
      onChange(messages.filter((m) => m.id !== messageId));
    },
    [onChange, messages],
  );

  const handleChangeMessage = useCallback(
    (messageId: string, changes: Partial<LLMMessage>) => {
      onChange(
        messages.map((m) => (m.id !== messageId ? m : { ...m, ...changes })),
      );
    },
    [onChange, messages],
  );

  const handleReplaceWithChatPrompt = useCallback(
    (newMessages: LLMMessage[]) => {
      // Replace all messages with the chat prompt's messages
      onChange(newMessages);
    },
    [onChange],
  );

  const handleClearOtherPromptLinks = useCallback(
    (currentMessageId: string) => () => {
      // Clear prompt links from all messages except the current one
      onChange(
        messages.map((m) =>
          m.id !== currentMessageId
            ? { ...m, promptId: undefined, promptVersionId: undefined }
            : m,
        ),
      );
    },
    [onChange, messages],
  );

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;

      const messageMap = keyBy(messages, "id");
      const localOrder = messages.map((m) => m.id);

      if (over && active.id !== over.id) {
        const oldIndex = localOrder.indexOf(active.id as string);
        const newIndex = localOrder.indexOf(over.id as string);

        const newOrder = arrayMove(localOrder, oldIndex, newIndex);
        const newMessageOrder = newOrder.map(
          (messageId) => messageMap[messageId],
        );

        onChange(newMessageOrder);
      }
    },
    [onChange, messages],
  );

  const handleMessageFocus = useCallback((messageId: string) => {
    lastFocusedMessageIdRef.current = messageId;
  }, []);

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      modifiers={[restrictToVerticalAxis]}
      onDragEnd={handleDragEnd}
    >
      <SortableContext items={messages} strategy={verticalListSortingStrategy}>
        <div ref={listRef} className="flex flex-col gap-2">
          {messages.map((message, messageIdx) => (
            <LLMPromptMessage
              key={message.id}
              ref={(handle) =>
                handle
                  ? messageRefsMap.current.set(message.id, handle)
                  : messageRefsMap.current.delete(message.id)
              }
              possibleTypes={possibleTypes}
              errorText={validationErrors?.[messageIdx]?.content?.message}
              hideRemoveButton={messages?.length === 1}
              hideDragButton={messages?.length === 1}
              hidePromptActions={hidePromptActions}
              onRemoveMessage={() => handleRemoveMessage(message.id)}
              onDuplicateMessage={() =>
                handleDuplicateMessage(message, messageIdx + 1)
              }
              onChangeMessage={(changes) =>
                handleChangeMessage(message.id, changes)
              }
              onReplaceWithChatPrompt={handleReplaceWithChatPrompt}
              onClearOtherPromptLinks={handleClearOtherPromptLinks(message.id)}
              onFocus={() => handleMessageFocus(message.id)}
              message={message}
              disableMedia={disableMedia}
              promptVariables={promptVariables}
              improvePromptConfig={improvePromptConfig}
              jsonTreeData={jsonTreeData}
            />
          ))}
        </div>
      </SortableContext>

      {!hideAddButton && (
        <Button
          variant="ghost"
          size="sm"
          className="mt-2 self-start"
          onClick={() => {
            onAddMessage();
            requestAnimationFrame(() => {
              const scrollContainer = listRef.current?.closest(
                "[data-scroll-container]",
              );
              if (scrollContainer) {
                scrollContainer.scrollTo({
                  top: scrollContainer.scrollHeight,
                  behavior: "smooth",
                });
              }
            });
          }}
          type="button"
        >
          <Plus className="mr-2 size-4" />
          Message
        </Button>
      )}
    </DndContext>
  );
};

export default LLMPromptMessages;
