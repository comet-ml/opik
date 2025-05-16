import React, { useRef, useState } from "react";
import { ChevronDown, CopyPlus, GripHorizontal, Trash } from "lucide-react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";

import { Button } from "@/components/ui/button";
import { FormErrorSkeleton } from "@/components/ui/form";
import { Card, CardContent } from "@/components/ui/card";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";

import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import Loader from "@/components/shared/Loader/Loader";
import { mustachePlugin } from "@/constants/codeMirrorPlugins";
import { DropdownOption } from "@/types/shared";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import LLMPromptMessageActions from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessageActions";

const MESSAGE_TYPE_OPTIONS = [
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.system],
    value: LLM_MESSAGE_ROLE.system,
  },
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.assistant],
    value: LLM_MESSAGE_ROLE.assistant,
  },
  {
    label: LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE.user],
    value: LLM_MESSAGE_ROLE.user,
  },
];

const theme = EditorView.theme({
  "&": {
    fontSize: "0.875rem",
    cursor: "text",
  },
  "&.cm-focused": {
    outline: "none",
  },
  ".cm-line": {
    "padding-left": 0,
  },
  ".cm-scroller": {
    fontFamily: "inherit",
  },
  ".cm-placeholder": {
    color: "#94A3B8",
    fontWeight: 300,
  },
});

interface LLMPromptMessageProps {
  message: LLMMessage;
  hideRemoveButton: boolean;
  hideDragButton: boolean;
  hidePromptActions: boolean;
  showAlwaysActionsPanel?: boolean;
  onRemoveMessage: () => void;
  onDuplicateMessage: () => void;
  errorText?: string;
  possibleTypes?: DropdownOption<LLM_MESSAGE_ROLE>[];
  onChangeMessage: (changes: Partial<LLMMessage>) => void;
}

const LLMPromptMessage = ({
  message,
  hideRemoveButton,
  hideDragButton,
  hidePromptActions,
  showAlwaysActionsPanel = false,
  errorText,
  possibleTypes = MESSAGE_TYPE_OPTIONS,
  onChangeMessage,
  onDuplicateMessage,
  onRemoveMessage,
}: LLMPromptMessageProps) => {
  const [isHoldActionsVisible, setIsHoldActionsVisible] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const { id, role, content } = message;

  const { active, attributes, listeners, setNodeRef, transform, transition } =
    useSortable({ id });

  const editorViewRef = useRef<EditorView | null>(null);
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  return (
    <>
      <Card
        key={id}
        style={style}
        ref={setNodeRef}
        onClick={() => {
          editorViewRef.current?.focus();
        }}
        {...attributes}
        className={cn(
          "group py-2 px-3 relative [&:focus-within]:border-primary",
          {
            "z-10": id === active?.id,
            "border-destructive": Boolean(errorText),
          },
        )}
      >
        <div
          className={cn(
            "absolute right-2 top-2 gap-1 group-hover:flex",
            showAlwaysActionsPanel || isHoldActionsVisible ? "flex" : "hidden",
          )}
        >
          {!hidePromptActions && (
            <LLMPromptMessageActions
              message={message}
              onChangeMessage={onChangeMessage}
              setIsLoading={setIsLoading}
              setIsHoldActionsVisible={setIsHoldActionsVisible}
            />
          )}
          {!hideRemoveButton && (
            <TooltipWrapper content="Delete a message">
              <Button
                variant="outline"
                size="icon-sm"
                onClick={onRemoveMessage}
                type="button"
              >
                <Trash />
              </Button>
            </TooltipWrapper>
          )}
          <TooltipWrapper content="Duplicate a message">
            <Button
              variant="outline"
              size="icon-sm"
              onClick={onDuplicateMessage}
              type="button"
            >
              <CopyPlus />
            </Button>
          </TooltipWrapper>
          {!hideDragButton && (
            <Button
              variant="outline"
              className="cursor-move"
              size="icon-sm"
              type="button"
              {...listeners}
            >
              <GripHorizontal />
            </Button>
          )}
        </div>

        <CardContent className="p-0">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="minimal" size="sm" className="min-w-4 p-0">
                {LLM_MESSAGE_ROLE_NAME_MAP[role] || role}
                <ChevronDown className="ml-1 w-4" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start">
              {possibleTypes.map(({ label, value }) => {
                return (
                  <DropdownMenuCheckboxItem
                    key={value}
                    onSelect={() => onChangeMessage({ role: value })}
                    checked={role === value}
                  >
                    {label}
                  </DropdownMenuCheckboxItem>
                );
              })}
            </DropdownMenuContent>
          </DropdownMenu>
          {isLoading ? (
            <Loader className="min-h-32" />
          ) : (
            <CodeMirror
              onCreateEditor={(view) => {
                editorViewRef.current = view;
              }}
              theme={theme}
              value={content}
              onChange={(c) => onChangeMessage({ content: c })}
              placeholder="Type your message"
              basicSetup={{
                foldGutter: false,
                allowMultipleSelections: false,
                lineNumbers: false,
                highlightActiveLine: false,
              }}
              extensions={[EditorView.lineWrapping, mustachePlugin]}
            />
          )}
        </CardContent>
      </Card>
      {errorText && (
        <FormErrorSkeleton className="-mt-2">{errorText}</FormErrorSkeleton>
      )}
    </>
  );
};

export default LLMPromptMessage;
