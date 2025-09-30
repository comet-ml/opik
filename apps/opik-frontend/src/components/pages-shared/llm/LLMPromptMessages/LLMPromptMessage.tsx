import React, { useEffect, useMemo, useRef, useState } from "react";
import {
  ChevronDown,
  CopyPlus,
  GripHorizontal,
  Image as ImageIcon,
  Trash,
  Type,
} from "lucide-react";
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

import {
  LLM_MESSAGE_ROLE,
  LLMMessage,
  LLMMessageContentItem,
} from "@/types/llm";

import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import Loader from "@/components/shared/Loader/Loader";
import { mustachePlugin } from "@/constants/codeMirrorPlugins";
import { DropdownOption } from "@/types/shared";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import LLMPromptMessageActions from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessageActions";
import { Input } from "@/components/ui/input";
import {
  getMessageContentTextSegments,
  getMessageContentImageSegments,
  isStructuredMessageContent,
} from "@/lib/llm";

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

const MAX_IMAGE_PARTS = 4;

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
    color: "var(--codemirror-gutter)",
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

  const isStructured = isStructuredMessageContent(content);
  const structuredContent = useMemo<LLMMessageContentItem[]>(
    () => (isStructured ? content : []),
    [content, isStructured],
  );
  const imagePartsCount = useMemo(
    () => getMessageContentImageSegments(content).length,
    [content],
  );
  const canAddMoreImages = imagePartsCount < MAX_IMAGE_PARTS;

  const canConvertToText = useMemo(
    () =>
      isStructured &&
      structuredContent.length > 0 &&
      structuredContent.every((item) => item.type === "text"),
    [isStructured, structuredContent],
  );

  const setStructuredContent = (items: LLMMessageContentItem[]) => {
    if (items.length === 0) {
      onChangeMessage({ content: "" });
      return;
    }

    if (items.length === 1 && items[0].type === "text") {
      onChangeMessage({ content: items[0].text });
      return;
    }

    onChangeMessage({ content: items });
  };

  useEffect(() => {
    if (
      isStructured &&
      structuredContent.length > 0 &&
      structuredContent.every((item) => item.type !== "text")
    ) {
      setStructuredContent([{ type: "text", text: "" }, ...structuredContent]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isStructured, structuredContent]);

  const handleAddImagePart = () => {
    if (!canAddMoreImages) {
      return;
    }

    if (isStructured) {
      setStructuredContent([
        ...structuredContent,
        { type: "image_url", image_url: { url: "" } },
      ]);
      return;
    }

    const parts: LLMMessageContentItem[] = [];

    if (typeof content === "string" && content.trim().length > 0) {
      parts.push({ type: "text", text: content });
    }

    parts.push({ type: "image_url", image_url: { url: "" } });

    setStructuredContent(parts);
  };

  const handleAddTextPart = () => {
    if (isStructured) {
      setStructuredContent([...structuredContent, { type: "text", text: "" }]);
      return;
    }

    onChangeMessage({ content: `${content ?? ""}` });
  };

  const renderAddImageButton = () => {
    const button = (
      <Button
        variant="outline"
        size="sm"
        type="button"
        onClick={handleAddImagePart}
        disabled={!canAddMoreImages}
      >
        <ImageIcon className="mr-2 size-4" /> Add image
      </Button>
    );

    if (canAddMoreImages) {
      return button;
    }

    return (
      <TooltipWrapper
        content={`Maximum of ${MAX_IMAGE_PARTS} images per message`}
      >
        <span className="inline-flex">{button}</span>
      </TooltipWrapper>
    );
  };

  const renderStructuredParts = () => {
    let textPartOrdinal = 0;
    let imagePartOrdinal = 0;

    const normalizedContent = structuredContent.some(
      (item) => item.type === "text",
    )
      ? structuredContent
      : ([
          { type: "text", text: "" },
          ...structuredContent,
        ] as LLMMessageContentItem[]);

    return normalizedContent.map((item, index) => {
      const isText = item.type === "text";
      const ordinal = isText ? ++textPartOrdinal : ++imagePartOrdinal;
      const label = isText
        ? textPartOrdinal === 1
          ? "Text"
          : `Text block ${ordinal}`
        : `Image ${ordinal}`;

      let partContent: React.ReactNode = null;

      if (isText) {
        partContent = (
          <CodeMirror
            onCreateEditor={(view) => {
              if (index === 0) {
                editorViewRef.current = view;
              }
            }}
            theme={theme}
            value={item.text}
            onChange={(value) => handleStructuredTextChange(index, value)}
            placeholder="Type your message"
            basicSetup={{
              foldGutter: false,
              allowMultipleSelections: false,
              lineNumbers: false,
              highlightActiveLine: false,
            }}
            extensions={[EditorView.lineWrapping, mustachePlugin]}
          />
        );
      } else {
        partContent = null;
      }

      return (
        <div
          key={`${id}-structured-${index}`}
          className="rounded-md border border-border p-3"
        >
          <div className="mb-2 flex items-center justify-between">
            <span className="comet-body-s text-muted-slate">{label}</span>
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => handleRemovePart(index)}
              type="button"
              aria-label="Remove content part"
            >
              <Trash />
            </Button>
          </div>

          {item.type === "image_url" ? (
            <Input
              value={item.image_url.url}
              onChange={(event) =>
                handleStructuredImageUrlChange(index, event.target.value)
              }
              placeholder="Image URL or {{input.image_url}}"
              onFocus={(event) => event.stopPropagation()}
              onClick={(event) => event.stopPropagation()}
              onMouseDown={(event) => event.stopPropagation()}
              data-prevent-editor-focus="true"
              aria-label={`Image URL for message part ${ordinal}`}
            />
          ) : (
            partContent
          )}
        </div>
      );
    });
  };

  const handleStructuredTextChange = (index: number, value: string) => {
    if (!isStructured) return;

    const updated = structuredContent.map((item, itemIndex) => {
      if (itemIndex !== index) {
        return item;
      }

      if (item.type !== "text") {
        return item;
      }

      return { ...item, text: value };
    });

    setStructuredContent(updated);
  };

  const handleStructuredImageUrlChange = (index: number, value: string) => {
    if (!isStructured) return;

    const updated = structuredContent.map((item, itemIndex) => {
      if (itemIndex !== index) {
        return item;
      }

      if (item.type !== "image_url") {
        return item;
      }

      return {
        ...item,
        image_url: { ...item.image_url, url: value },
      };
    });

    setStructuredContent(updated);
  };

  const handleRemovePart = (index: number) => {
    if (!isStructured) return;

    setStructuredContent(
      structuredContent.filter((_, itemIndex) => itemIndex !== index),
    );
  };

  const handleConvertToText = () => {
    if (!isStructured) return;

    const textValue =
      getMessageContentTextSegments(structuredContent).join("\n\n");

    onChangeMessage({ content: textValue });
  };

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
        onClick={(event) => {
          if (event.target instanceof HTMLElement) {
            const interactiveSelector =
              'input, textarea, button, [role="combobox"], [data-prevent-editor-focus="true"]';
            if (event.target.closest(interactiveSelector)) {
              return;
            }
          }

          editorViewRef.current?.focus();
        }}
        {...attributes}
        className={cn("group py-2 px-3 [&:focus-within]:border-primary", {
          "z-10": id === active?.id,
          "border-destructive": Boolean(errorText),
        })}
      >
        <CardContent className="p-0">
          <div className="sticky top-0 z-10 flex items-center justify-between gap-2 bg-background shadow-[0_6px_6px_-1px_hsl(var(--background))]">
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
            <div
              className={cn(
                "gap-2 group-hover:flex",
                showAlwaysActionsPanel || isHoldActionsVisible
                  ? "flex"
                  : "hidden",
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
                    aria-label="Delete message"
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
                  aria-label="Duplicate message"
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
                  aria-label="Reorder message"
                  {...listeners}
                >
                  <GripHorizontal />
                </Button>
              )}
            </div>
          </div>

          {isLoading ? (
            <Loader className="min-h-32" />
          ) : isStructured ? (
            <div className="flex flex-col gap-3">
              {structuredContent.length === 0 ? (
                <div className="rounded-md border border-dashed border-border p-4 text-center text-muted-slate">
                  No content. Add a text or image part below.
                </div>
              ) : null}
              {renderStructuredParts()}

              <div className="flex flex-wrap gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  type="button"
                  onClick={handleAddTextPart}
                >
                  <Type className="mr-2 size-4" /> Add text
                </Button>
                {renderAddImageButton()}
                {canConvertToText ? (
                  <Button
                    variant="ghost"
                    size="sm"
                    type="button"
                    onClick={handleConvertToText}
                  >
                    Convert to text message
                  </Button>
                ) : null}
              </div>
            </div>
          ) : (
            <>
              <CodeMirror
                onCreateEditor={(view) => {
                  editorViewRef.current = view;
                }}
                theme={theme}
                value={typeof content === "string" ? content : ""}
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
              <div className="mt-2 flex flex-wrap gap-2">
                {renderAddImageButton()}
              </div>
            </>
          )}
        </CardContent>
      </Card>
      {errorText && <FormErrorSkeleton>{errorText}</FormErrorSkeleton>}
    </>
  );
};

export default LLMPromptMessage;
