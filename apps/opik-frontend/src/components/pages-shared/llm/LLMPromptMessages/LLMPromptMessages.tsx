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

import {
  appendTextToMessageContent,
  generateDefaultLLMPromptMessage,
} from "@/lib/llm";
import LLMPromptMessage, {
  LLMPromptMessageHandle,
} from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessage";
import { Button } from "@/components/ui/button";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { DropdownOption } from "@/types/shared";
import { ImprovePromptConfig } from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessageActions";
import PromptVariablesList from "@/components/pages-shared/llm/PromptVariablesList/PromptVariablesList";
import { JsonObject } from "@/components/shared/JsonTreePopover";

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
  disabled?: boolean;
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
  disabled = false,
  jsonTreeData,
}: LLMPromptMessagesProps) => {
  const lastFocusedMessageIdRef = useRef<string | null>(null);
  const messageRefsMap = useRef<Map<string, LLMPromptMessageHandle>>(new Map());

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

  const handleVariableClick = useCallback(
    (variable: string) => {
      if (messages.length === 0) return;

      const variableText = `{{${variable}}}`;
      const lastMessageId = messages[messages.length - 1].id;

      // use last focused message if it still exists, otherwise fall back to last message
      const focusedMessageExists =
        lastFocusedMessageIdRef.current &&
        messages.some((m) => m.id === lastFocusedMessageIdRef.current);

      const targetMessageId = focusedMessageExists
        ? lastFocusedMessageIdRef.current!
        : lastMessageId;

      const messageRef = messageRefsMap.current.get(targetMessageId);
      if (messageRef) {
        messageRef.insertAtCursor(variableText);
        return;
      }

      // fallback: append to message content while preserving structure
      const targetMessage = messages.find((m) => m.id === targetMessageId);
      if (targetMessage) {
        const newContent = appendTextToMessageContent(
          targetMessage.content,
          variableText,
        );
        onChange(
          messages.map((m) =>
            m.id === targetMessageId ? { ...m, content: newContent } : m,
          ),
        );
      }
    },
    [messages, onChange],
  );

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      modifiers={[restrictToVerticalAxis]}
      onDragEnd={handleDragEnd}
    >
      <div className="comet-no-scrollbar h-[calc(100%-30px)] overflow-y-auto">
        <SortableContext
          items={messages}
          strategy={verticalListSortingStrategy}
        >
          <div className="flex flex-col gap-2">
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
                showAlwaysActionsPanel={messageIdx === messages.length - 1}
                onRemoveMessage={() => handleRemoveMessage(message.id)}
                onDuplicateMessage={() =>
                  handleDuplicateMessage(message, messageIdx + 1)
                }
                onChangeMessage={(changes) =>
                  handleChangeMessage(message.id, changes)
                }
                onReplaceWithChatPrompt={handleReplaceWithChatPrompt}
                onClearOtherPromptLinks={handleClearOtherPromptLinks(
                  message.id,
                )}
                onFocus={() => handleMessageFocus(message.id)}
                message={message}
                disableMedia={disableMedia}
                promptVariables={promptVariables}
                improvePromptConfig={improvePromptConfig}
                disabled={disabled}
                jsonTreeData={jsonTreeData}
              />
            ))}
          </div>
        </SortableContext>

        {promptVariables.length > 0 && (
          <p className="comet-body-s mt-2 text-light-slate">
            Use {"{{variable_name}}"} syntax to reference dataset variables in
            your prompt:{" "}
            <PromptVariablesList
              variables={promptVariables}
              onVariableClick={handleVariableClick}
              tooltipContent="Click to insert into prompt"
            />
          </p>
        )}

        {!hideAddButton && (
          <Button
            variant="outline"
            size="sm"
            className="mt-2"
            onClick={onAddMessage}
            type="button"
          >
            <Plus className="mr-2 size-4" />
            Message
          </Button>
        )}
      </div>
    </DndContext>
  );
};

export default LLMPromptMessages;
