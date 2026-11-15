import React, { useCallback, useEffect, useRef } from "react";
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

export interface MessageValidationError {
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
  compact?: boolean;
  autoFocusFirstMessage?: boolean;
  shiftEnterInsertsNewline?: boolean;
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
  compact = false,
  autoFocusFirstMessage = false,
  shiftEnterInsertsNewline = false,
}: LLMPromptMessagesProps) => {
  const lastFocusedMessageIdRef = useRef<string | null>(null);
  const messageRefsMap = useRef<Map<string, LLMPromptMessageHandle>>(new Map());
  const listRef = useRef<HTMLDivElement>(null);
  const idsBeforeAddRef = useRef<Set<string> | null>(null);
  const didAutoFocusRef = useRef(false);

  // Two editors can fire before the parent re-renders with the first change, so each
  // change builds on the last array emitted. Re-sync only when the parent passes a new
  // array: a render still carrying the old one must not roll the pending change back.
  const propMessagesRef = useRef(messages);
  const latestMessagesRef = useRef(messages);
  if (propMessagesRef.current !== messages) {
    propMessagesRef.current = messages;
    latestMessagesRef.current = messages;
  }

  const emitChange = useCallback(
    (newMessages: LLMMessage[]) => {
      latestMessagesRef.current = newMessages;
      onChange(newMessages);
    },
    [onChange],
  );

  useEffect(() => {
    const idsBeforeAdd = idsBeforeAddRef.current;
    if (!idsBeforeAdd) return;
    const addedMessage = messages.find(({ id }) => !idsBeforeAdd.has(id));
    if (!addedMessage) return;
    idsBeforeAddRef.current = null;
    messageRefsMap.current.get(addedMessage.id)?.focus();
  }, [messages]);

  useEffect(() => {
    if (didAutoFocusRef.current) return;
    didAutoFocusRef.current = true;
    if (!autoFocusFirstMessage) return;
    const { activeElement } = document;
    if (activeElement && activeElement !== document.body) return;
    messageRefsMap.current.get(messages[0]?.id)?.focus();
  }, [autoFocusFirstMessage, messages]);

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
      const newMessages = [...latestMessagesRef.current];

      newMessages.splice(position, 0, newMessage);

      emitChange(newMessages);
      requestAnimationFrame(() => {
        listRef.current?.children[position]?.scrollIntoView({
          behavior: "smooth",
          block: "nearest",
        });
      });
    },
    [emitChange],
  );

  const handleRemoveMessage = useCallback(
    (messageId: string) => {
      emitChange(latestMessagesRef.current.filter((m) => m.id !== messageId));
    },
    [emitChange],
  );

  const handleChangeMessage = useCallback(
    (messageId: string, changes: Partial<LLMMessage>) => {
      emitChange(
        latestMessagesRef.current.map((m) =>
          m.id !== messageId ? m : { ...m, ...changes },
        ),
      );
    },
    [emitChange],
  );

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;
      const current = latestMessagesRef.current;

      const messageMap = keyBy(current, "id");
      const localOrder = current.map((m) => m.id);

      if (over && active.id !== over.id) {
        const oldIndex = localOrder.indexOf(active.id as string);
        const newIndex = localOrder.indexOf(over.id as string);

        const newOrder = arrayMove(localOrder, oldIndex, newIndex);
        const newMessageOrder = newOrder.map(
          (messageId) => messageMap[messageId],
        );

        emitChange(newMessageOrder);
      }
    },
    [emitChange],
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
        <div ref={listRef} className="flex flex-col gap-1.5">
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
              onFocus={() => handleMessageFocus(message.id)}
              message={message}
              disableMedia={disableMedia}
              promptVariables={promptVariables}
              improvePromptConfig={improvePromptConfig}
              jsonTreeData={jsonTreeData}
              compact={compact}
              shiftEnterInsertsNewline={shiftEnterInsertsNewline}
            />
          ))}
        </div>
      </SortableContext>

      {!hideAddButton && (
        <Button
          variant="ghost"
          size="2xs"
          className="mt-1 self-start px-1"
          onClick={() => {
            idsBeforeAddRef.current = new Set(messages.map(({ id }) => id));
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
          <Plus className="mr-1 size-3" />
          Message
        </Button>
      )}
    </DndContext>
  );
};

export default LLMPromptMessages;
