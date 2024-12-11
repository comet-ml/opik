import React, { useRef } from "react";
import { Button } from "@/components/ui/button";
import { ChevronDown, CopyPlus, GripHorizontal, Trash } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import capitalize from "lodash/capitalize";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import {
  PLAYGROUND_MESSAGE_TYPE,
  PlaygroundMessageType,
} from "@/types/playgroundPrompts";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { cn } from "@/lib/utils";

const MESSAGE_TYPE_OPTIONS = Object.values(PLAYGROUND_MESSAGE_TYPE);

const theme = EditorView.theme({
  "&": {
    fontSize: "0.875rem",
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
    // ALEX
    color: "#94A3B8",
    fontWeight: 300,
  },
});

// ALEX CURSOR
// ALEX TO CHECK WHY IT"S TWEAING WHEN MOVING

interface PlaygroundPromptMessageProps extends PlaygroundMessageType {
  hideRemoveButton: boolean;
  onRemoveMessage: () => void;
  onDuplicateMessage: () => void;
  onChangeMessage: (changes: Partial<PlaygroundMessageType>) => void;
}

const PlaygroundPromptMessage = ({
  id,
  type,
  text,
  hideRemoveButton,
  onChangeMessage,
  onDuplicateMessage,
  onRemoveMessage,
}: PlaygroundPromptMessageProps) => {
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
      className={cn("group py-2 px-3 relative", { "z-10": id === active?.id })}
    >
      <div className="absolute right-2 top-2  gap-1 hidden group-hover:flex">
        {!hideRemoveButton && (
          <Button variant="outline" size="icon-sm" onClick={onRemoveMessage}>
            <Trash className="size-3.5" />
          </Button>
        )}
        <Button variant="outline" size="icon-sm" onClick={onDuplicateMessage}>
          <CopyPlus className="size-3.5" />
        </Button>
        <Button variant="outline" size="icon-sm" {...listeners}>
          <GripHorizontal className="size-3.5" />
        </Button>
      </div>

      <CardContent className="p-0">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="minimal" size="sm" className="min-w-4 p-0">
              {capitalize(type)}
              <ChevronDown className="w-4 ml-1" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            {MESSAGE_TYPE_OPTIONS.map((t) => {
              return (
                <DropdownMenuCheckboxItem
                  key={t}
                  onSelect={() => onChangeMessage({ type: t })}
                  checked={type === t}
                >
                  {capitalize(t)}
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
          value={text}
          onChange={(t) => onChangeMessage({ text: t })}
          placeholder="Type your message"
          basicSetup={{
            foldGutter: false,
            allowMultipleSelections: false,
            lineNumbers: false,
            highlightActiveLine: false,
          }}
          extensions={[EditorView.lineWrapping]}
        />
      </CardContent>
    </Card>
  );
};

export default PlaygroundPromptMessage;
