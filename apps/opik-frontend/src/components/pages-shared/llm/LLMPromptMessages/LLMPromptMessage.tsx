import React, { useRef } from "react";
import { ChevronDown, CopyPlus, GripHorizontal, Trash } from "lucide-react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import capitalize from "lodash/capitalize";

import { Button } from "@/components/ui/button";
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
import { mustachePlugin } from "@/constants/codeMirrorPlugins";

const MESSAGE_TYPE_OPTIONS = Object.values(LLM_MESSAGE_ROLE);

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

interface LLMPromptMessageProps extends LLMMessage {
  hideRemoveButton: boolean;
  hideDragButton: boolean;
  onRemoveMessage: () => void;
  onDuplicateMessage: () => void;
  onChangeMessage: (changes: Partial<LLMMessage>) => void;
}

const LLMPromptMessage = ({
  id,
  content,
  role,
  hideRemoveButton,
  hideDragButton,
  onChangeMessage,
  onDuplicateMessage,
  onRemoveMessage,
}: LLMPromptMessageProps) => {
  const { active, attributes, listeners, setNodeRef, transform, transition } =
    useSortable({ id });

  const editorViewRef = useRef<EditorView | null>(null);
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  return (
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
        },
      )}
    >
      <div className="absolute right-2 top-2 hidden gap-1 group-hover:flex">
        {!hideRemoveButton && (
          <TooltipWrapper content="Delete a message">
            <Button variant="outline" size="icon-sm" onClick={onRemoveMessage}>
              <Trash className="size-3.5" />
            </Button>
          </TooltipWrapper>
        )}
        <TooltipWrapper content="Duplicate a message">
          <Button variant="outline" size="icon-sm" onClick={onDuplicateMessage}>
            <CopyPlus className="size-3.5" />
          </Button>
        </TooltipWrapper>
        {!hideDragButton && (
          <Button
            variant="outline"
            className="cursor-move"
            size="icon-sm"
            {...listeners}
          >
            <GripHorizontal className="size-3.5" />
          </Button>
        )}
      </div>

      <CardContent className="p-0">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="minimal" size="sm" className="min-w-4 p-0">
              {capitalize(role)}
              <ChevronDown className="ml-1 w-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            {MESSAGE_TYPE_OPTIONS.map((r) => {
              return (
                <DropdownMenuCheckboxItem
                  key={r}
                  onSelect={() => onChangeMessage({ role: r })}
                  checked={role === r}
                >
                  {capitalize(r)}
                </DropdownMenuCheckboxItem>
              );
            })}
          </DropdownMenuContent>
        </DropdownMenu>
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
      </CardContent>
    </Card>
  );
};

export default LLMPromptMessage;
