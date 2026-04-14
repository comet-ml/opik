import React, { useCallback } from "react";
import capitalize from "lodash/capitalize";
import { CopyPlus, GripHorizontal, Plus, Trash2 } from "lucide-react";
import {
  closestCenter,
  DndContext,
  MouseSensor,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import type { DragEndEvent } from "@dnd-kit/core/dist/types";
import {
  arrayMove,
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { restrictToVerticalAxis } from "@dnd-kit/modifiers";
import { CSS } from "@dnd-kit/utilities";

import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { Button } from "@/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import AutoResizeTextarea from "./AutoResizeTextarea";
import CollapsibleBlock from "./CollapsibleBlock";

const EDITABLE_ROLES = [
  LLM_MESSAGE_ROLE.system,
  LLM_MESSAGE_ROLE.user,
  LLM_MESSAGE_ROLE.assistant,
];

const getRoleLabel = (role: string): string => {
  const roleKey = role.toUpperCase() as keyof typeof LLM_MESSAGE_ROLE;
  if (LLM_MESSAGE_ROLE[roleKey]) {
    return LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE[roleKey]] || role;
  }
  return capitalize(role);
};

const getContentText = (content: LLMMessage["content"]): string => {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content
      .map((part) => (part.type === "text" ? part.text ?? "" : ""))
      .filter(Boolean)
      .join("\n\n");
  }
  return "";
};

type SortableMessageProps = {
  message: LLMMessage;
  index: number;
  messages: LLMMessage[];
  isExpanded: boolean;
  onToggle: () => void;
  editable: boolean;
  onChangeMessage?: (index: number, content: string) => void;
  onChangeRole?: (index: number, role: LLM_MESSAGE_ROLE) => void;
  onDeleteMessage?: (index: number) => void;
  onDuplicateMessage?: (index: number) => void;
  tone?: "muted" | "white";
};

const SortableMessage: React.FC<SortableMessageProps> = ({
  message,
  index,
  messages,
  isExpanded,
  onToggle,
  editable,
  onChangeMessage,
  onChangeRole,
  onDeleteMessage,
  onDuplicateMessage,
  tone,
}) => {
  const { attributes, listeners, setNodeRef, transform, transition } =
    useSortable({ id: message.id });

  const style = {
    transform: CSS.Translate.toString(transform),
    transition,
  };

  const text = getContentText(message.content);
  const canDelete = editable && messages.length > 1;

  const roleSelector =
    editable && onChangeRole ? (
      <Select
        value={message.role}
        onValueChange={(v) => onChangeRole(index, v as LLM_MESSAGE_ROLE)}
      >
        <SelectTrigger className="h-5 w-auto gap-1 border-0 bg-transparent px-0 text-xs font-medium shadow-none">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {EDITABLE_ROLES.map((role) => (
            <SelectItem key={role} value={role}>
              {getRoleLabel(role)}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    ) : undefined;

  const dragHandle =
    editable && messages.length > 1 ? (
      <button
        type="button"
        className="cursor-grab touch-none text-light-slate hover:text-foreground active:cursor-grabbing"
        {...listeners}
        {...attributes}
      >
        <GripHorizontal className="size-3.5" />
      </button>
    ) : undefined;

  return (
    <div ref={setNodeRef} style={style}>
      <CollapsibleBlock
        label={editable ? undefined : getRoleLabel(message.role)}
        collapsible
        expanded={isExpanded}
        onToggle={onToggle}
        tone={tone}
        headerPrefix={
          editable ? (
            <div className="flex items-center gap-1">
              {dragHandle}
              {roleSelector}
            </div>
          ) : undefined
        }
        trailing={
          editable ? (
            <div className="flex items-center gap-0.5">
              {onDuplicateMessage && (
                <TooltipWrapper content="Duplicate">
                  <Button
                    variant="minimal"
                    size="icon-2xs"
                    onClick={() => onDuplicateMessage(index)}
                  >
                    <CopyPlus className="size-3" />
                  </Button>
                </TooltipWrapper>
              )}
              {onDeleteMessage && canDelete && (
                <TooltipWrapper content="Delete">
                  <Button
                    variant="minimal"
                    size="icon-2xs"
                    onClick={() => onDeleteMessage(index)}
                  >
                    <Trash2 className="size-3 text-destructive" />
                  </Button>
                </TooltipWrapper>
              )}
            </div>
          ) : undefined
        }
      >
        {editable ? (
          <AutoResizeTextarea
            value={text}
            onChange={(v) => onChangeMessage?.(index, v)}
          />
        ) : (
          <div className="comet-body-s whitespace-pre-wrap break-words text-foreground">
            {text}
          </div>
        )}
      </CollapsibleBlock>
    </div>
  );
};

type BlueprintChatMessagesProps = {
  messages: LLMMessage[];
  isExpanded: (index: number) => boolean;
  onToggle: (index: number) => void;
  editable?: boolean;
  onChangeMessage?: (index: number, content: string) => void;
  onChangeRole?: (index: number, role: LLM_MESSAGE_ROLE) => void;
  onAddMessage?: () => void;
  onDeleteMessage?: (index: number) => void;
  onDuplicateMessage?: (index: number) => void;
  onReorder?: (messages: LLMMessage[]) => void;
  tone?: "muted" | "white";
};

const BlueprintChatMessages: React.FC<BlueprintChatMessagesProps> = ({
  messages,
  isExpanded,
  onToggle,
  editable = false,
  onChangeMessage,
  onChangeRole,
  onAddMessage,
  onDeleteMessage,
  onDuplicateMessage,
  onReorder,
  tone,
}) => {
  const sensors = useSensors(
    useSensor(MouseSensor, { activationConstraint: { distance: 5 } }),
  );

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;
      if (!over || active.id === over.id || !onReorder) return;

      const oldIndex = messages.findIndex((m) => m.id === active.id);
      const newIndex = messages.findIndex((m) => m.id === over.id);
      if (oldIndex === -1 || newIndex === -1) return;

      onReorder(arrayMove(messages, oldIndex, newIndex));
    },
    [messages, onReorder],
  );

  const content = (
    <div className="flex flex-col gap-2">
      {messages.map((message, index) => (
        <SortableMessage
          key={message.id || index}
          message={message}
          index={index}
          messages={messages}
          isExpanded={isExpanded(index)}
          onToggle={() => onToggle(index)}
          editable={editable}
          onChangeMessage={onChangeMessage}
          onChangeRole={onChangeRole}
          onDeleteMessage={onDeleteMessage}
          onDuplicateMessage={onDuplicateMessage}
          tone={tone}
        />
      ))}
      {editable && onAddMessage && (
        <Button
          variant="outline"
          size="2xs"
          onClick={onAddMessage}
          className="w-fit"
        >
          <Plus className="mr-1 size-3" />
          Message
        </Button>
      )}
    </div>
  );

  if (editable && onReorder) {
    return (
      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        modifiers={[restrictToVerticalAxis]}
        onDragEnd={handleDragEnd}
      >
        <SortableContext
          items={messages}
          strategy={verticalListSortingStrategy}
        >
          {content}
        </SortableContext>
      </DndContext>
    );
  }

  return content;
};

export default BlueprintChatMessages;
