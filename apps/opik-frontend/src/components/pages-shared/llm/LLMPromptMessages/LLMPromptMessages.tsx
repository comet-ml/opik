import React, { useCallback } from "react";
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
import { SortableContext } from "@dnd-kit/sortable";

import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import LLMPromptMessage from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessage";
import { Button } from "@/components/ui/button";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { DropdownOption } from "@/types/shared";

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
  hint?: string;
}

const LLMPromptMessages = ({
  messages,
  validationErrors,
  possibleTypes,
  hidePromptActions = true,
  onChange,
  onAddMessage,
  hint = "",
}: LLMPromptMessagesProps) => {
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

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragEnd={handleDragEnd}
    >
      <div className="comet-no-scrollbar h-[calc(100%-30px)] overflow-y-auto">
        <SortableContext
          items={messages}
          strategy={verticalListSortingStrategy}
        >
          <div className="flex flex-col gap-2 overflow-hidden">
            {messages.map((message, messageIdx) => (
              <LLMPromptMessage
                key={message.id}
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
                message={message}
              />
            ))}
          </div>
        </SortableContext>

        {hint && <p className="comet-body-s mt-2 text-light-slate">{hint}</p>}

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
      </div>
    </DndContext>
  );
};

export default LLMPromptMessages;
