import React, {
  useRef,
  useState,
  useImperativeHandle,
  forwardRef,
  useCallback,
  useMemo,
} from "react";
import { ChevronDown, CopyPlus, GripHorizontal, Trash } from "lucide-react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView, keymap, ViewUpdate } from "@codemirror/view";
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
import {
  mustachePlugin,
  codeMirrorPromptTheme,
} from "@/constants/codeMirrorPlugins";
import { DropdownOption } from "@/types/shared";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import LLMPromptMessageActions, {
  ImprovePromptConfig,
} from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessageActions";
import PromptMessageMediaSection from "@/components/pages-shared/llm/PromptMessageMediaTags/PromptMessageMediaSection";
import { useMessageContent } from "@/hooks/useMessageContent";
import {
  getTextFromMessageContent,
  hasAudiosInContent,
  hasImagesInContent,
  hasVideosInContent,
  isMediaAllowedForRole,
} from "@/lib/llm";
import {
  JsonTreePopover,
  JsonObject,
  JsonValue,
} from "@/components/shared/JsonTreePopover";

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

export interface LLMPromptMessageHandle {
  insertAtCursor: (text: string) => void;
}

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
  onReplaceWithChatPrompt?: (
    messages: LLMMessage[],
    promptId: string,
    promptVersionId: string,
  ) => void;
  onClearOtherPromptLinks?: () => void;
  onFocus?: () => void;
  disableMedia?: boolean;
  promptVariables?: string[];
  improvePromptConfig?: ImprovePromptConfig;
  disabled?: boolean;
  /** JSON data for the variable picker popover */
  jsonTreeData?: JsonObject | null;
  /** Callback when a path is selected from the JSON tree */
  onJsonPathSelect?: (path: string, value: JsonValue) => void;
}

const LLMPromptMessage = forwardRef<
  LLMPromptMessageHandle,
  LLMPromptMessageProps
>(
  (
    {
      message,
      hideRemoveButton,
      hideDragButton,
      hidePromptActions,
      showAlwaysActionsPanel = false,
      errorText,
      possibleTypes = MESSAGE_TYPE_OPTIONS,
      onChangeMessage,
      onReplaceWithChatPrompt,
      onClearOtherPromptLinks,
      onFocus,
      onDuplicateMessage,
      onRemoveMessage,
      disableMedia = true,
      promptVariables,
      improvePromptConfig,
      disabled = false,
      jsonTreeData,
      onJsonPathSelect,
    },
    ref,
  ) => {
    const [isHoldActionsVisible, setIsHoldActionsVisible] = useState(false);
    const [isLoading, setIsLoading] = useState(false);
    const [isJsonPopoverOpen, setIsJsonPopoverOpen] = useState(false);
    const [jsonSearchQuery, setJsonSearchQuery] = useState("");
    const [braceStartPos, setBraceStartPos] = useState<number | null>(null);
    const { id, role, content } = message;

    const { active, attributes, listeners, setNodeRef, transform, transition } =
      useSortable({ id });

    const editorViewRef = useRef<EditorView | null>(null);
    const popoverTriggerRef = useRef<HTMLSpanElement | null>(null);
    const [popoverPosition, setPopoverPosition] = useState({ top: 0, left: 0 });
    const style = {
      transform: CSS.Transform.toString(transform),
      transition,
    };

    const {
      localText,
      images,
      videos,
      audios,
      setImages,
      setVideos,
      setAudios,
      handleContentChange,
    } = useMessageContent({
      content,
      onChangeContent: (newContent) => onChangeMessage({ content: newContent }),
    });

    const insertTextAtCursor = useCallback((text: string) => {
      const view = editorViewRef.current;
      if (view) {
        const cursorPos = view.state.selection.main.head;
        view.dispatch({
          changes: { from: cursorPos, insert: text },
          selection: { anchor: cursorPos + text.length },
        });
        view.focus();
      }
    }, []);

    useImperativeHandle(ref, () => ({
      insertAtCursor: insertTextAtCursor,
    }));

    const handleJsonPathSelect = useCallback(
      (path: string, value: JsonValue) => {
        const view = editorViewRef.current;
        if (view && braceStartPos !== null) {
          const cursorPos = view.state.selection.main.head;
          // Replace from after '{{' to current cursor with the path and closing '}}'
          // braceStartPos is right after '{{', so we insert 'path}}'
          view.dispatch({
            changes: {
              from: braceStartPos,
              to: cursorPos,
              insert: `${path}}}`,
            },
            selection: { anchor: braceStartPos + path.length + 2 }, // Position after }}
          });
          view.focus();
        } else {
          // Fallback: just insert full mustache syntax at cursor
          insertTextAtCursor(`{{${path}}}`);
        }
        setIsJsonPopoverOpen(false);
        setJsonSearchQuery("");
        setBraceStartPos(null);
        onJsonPathSelect?.(path, value);
      },
      [braceStartPos, insertTextAtCursor, onJsonPathSelect],
    );

    // Check if JSON tree data is available
    const hasJsonData = useMemo(() => {
      return jsonTreeData && Object.keys(jsonTreeData).length > 0;
    }, [jsonTreeData]);

    // Handle popover close - reset state
    const handlePopoverOpenChange = useCallback((open: boolean) => {
      setIsJsonPopoverOpen(open);
      if (!open) {
        setJsonSearchQuery("");
        setBraceStartPos(null);
      }
    }, []);

    // Track text changes while popover is open to update search query
    const handleEditorUpdate = useCallback(
      (update: ViewUpdate) => {
        if (isJsonPopoverOpen && braceStartPos !== null && update.docChanged) {
          const doc = update.state.doc;
          const cursorPos = update.state.selection.main.head;

          // Check if '{{' is still present before braceStartPos
          // braceStartPos is right after '{{', so we check the 2 chars before it
          const openingBraces =
            braceStartPos >= 2
              ? doc.sliceString(braceStartPos - 2, braceStartPos)
              : "";

          // If '{{' was deleted, close the popover
          if (openingBraces !== "{{") {
            setIsJsonPopoverOpen(false);
            setJsonSearchQuery("");
            setBraceStartPos(null);
            return;
          }

          // Extract text between '{{' and cursor (braceStartPos is right after '{{')
          if (cursorPos >= braceStartPos) {
            const textAfterBraces = doc.sliceString(braceStartPos, cursorPos);
            // Only update if it looks like a search query (no special chars that would close the variable)
            if (
              !textAfterBraces.includes("}") &&
              !textAfterBraces.includes("{")
            ) {
              setJsonSearchQuery(textAfterBraces);
            } else {
              // If user typed } or {, close the popover
              setIsJsonPopoverOpen(false);
              setJsonSearchQuery("");
              setBraceStartPos(null);
            }
          } else {
            // Cursor moved before braceStartPos, close popover
            setIsJsonPopoverOpen(false);
            setJsonSearchQuery("");
            setBraceStartPos(null);
          }
        }
      },
      [isJsonPopoverOpen, braceStartPos],
    );

    // CodeMirror extension to detect '{{' and '}}' sequences
    const braceKeyExtension = useMemo(() => {
      if (!hasJsonData) return null;

      return keymap.of([
        {
          key: "{",
          run: () => {
            const view = editorViewRef.current;
            if (view) {
              const cursorPos = view.state.selection.main.head;
              const doc = view.state.doc;

              // Check if the character before cursor is also '{'
              const charBefore =
                cursorPos > 0 ? doc.sliceString(cursorPos - 1, cursorPos) : "";

              // Insert the '{' character first
              view.dispatch({
                changes: { from: cursorPos, insert: "{" },
                selection: { anchor: cursorPos + 1 },
              });

              // If this is the second '{', open the popover
              if (charBefore === "{") {
                // Store the position after the opening '{{' (cursorPos was before the second {, now cursor is at cursorPos + 1)
                setBraceStartPos(cursorPos + 1);
                setJsonSearchQuery("");

                // Use requestMeasure to get accurate coordinates after DOM update
                view.requestMeasure({
                  read: () => {
                    const coords = view.coordsAtPos(cursorPos + 1);
                    const editorRect = view.dom.getBoundingClientRect();
                    return { coords, editorRect };
                  },
                  write: ({ coords, editorRect }) => {
                    if (coords && editorRect) {
                      // Calculate position relative to the editor container
                      setPopoverPosition({
                        top: coords.bottom - editorRect.top,
                        left: coords.left - editorRect.left,
                      });
                    }
                    setIsJsonPopoverOpen(true);
                  },
                });
              }
            }
            return true; // Prevent default handling
          },
        },
        {
          key: "}",
          run: () => {
            const view = editorViewRef.current;
            if (view) {
              const cursorPos = view.state.selection.main.head;
              const doc = view.state.doc;

              // Check if the character before cursor is also '}'
              const charBefore =
                cursorPos > 0 ? doc.sliceString(cursorPos - 1, cursorPos) : "";

              // Insert the '}' character
              view.dispatch({
                changes: { from: cursorPos, insert: "}" },
                selection: { anchor: cursorPos + 1 },
              });

              // If this is the second '}', close the popover
              if (charBefore === "}" && isJsonPopoverOpen) {
                setIsJsonPopoverOpen(false);
                setJsonSearchQuery("");
                setBraceStartPos(null);
              }
            }
            return true; // Prevent default handling
          },
        },
        {
          key: "[",
          run: () => {
            const view = editorViewRef.current;
            if (view) {
              const cursorPos = view.state.selection.main.head;

              // When popover is open, insert just '[' without auto-pairing
              if (isJsonPopoverOpen) {
                view.dispatch({
                  changes: { from: cursorPos, insert: "[" },
                  selection: { anchor: cursorPos + 1 },
                });
                return true; // Prevent default handling (including auto-pairing)
              }
            }
            return false; // Let default handling occur when popover is closed
          },
        },
      ]);
    }, [hasJsonData, isJsonPopoverOpen]);

    const handleRoleChange = (newRole: LLM_MESSAGE_ROLE) => {
      if (
        !isMediaAllowedForRole(newRole) &&
        (hasImagesInContent(content) ||
          hasVideosInContent(content) ||
          hasAudiosInContent(content))
      ) {
        const textOnlyContent = getTextFromMessageContent(content);
        onChangeMessage({ role: newRole, content: textOnlyContent });
      } else {
        onChangeMessage({ role: newRole });
      }
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
          className={cn("group py-2 px-3 [&:focus-within]:border-primary", {
            "z-10": id === active?.id,
            "border-destructive": Boolean(errorText),
          })}
        >
          <CardContent className="p-0">
            <div className="sticky top-0 z-10 flex items-center justify-between gap-2 bg-background shadow-[0_6px_6px_-1px_hsl(var(--background))] dark:bg-accent-background dark:shadow-none">
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button
                    variant="minimal"
                    size="sm"
                    className="min-w-4 p-0"
                    disabled={disabled}
                  >
                    {LLM_MESSAGE_ROLE_NAME_MAP[role] || role}
                    <ChevronDown className="ml-1 w-4" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start">
                  {possibleTypes.map(({ label, value }) => {
                    return (
                      <DropdownMenuCheckboxItem
                        key={value}
                        onSelect={() => handleRoleChange(value)}
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
                    onReplaceWithChatPrompt={onReplaceWithChatPrompt}
                    onClearOtherPromptLinks={onClearOtherPromptLinks}
                    setIsLoading={setIsLoading}
                    setIsHoldActionsVisible={setIsHoldActionsVisible}
                    improvePromptConfig={improvePromptConfig}
                    disabled={disabled}
                  />
                )}
                {!hideRemoveButton && (
                  <TooltipWrapper content="Delete a message">
                    <Button
                      variant="outline"
                      size="icon-sm"
                      onClick={onRemoveMessage}
                      type="button"
                      disabled={disabled}
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
                    disabled={disabled}
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
                    disabled={disabled}
                    {...listeners}
                  >
                    <GripHorizontal />
                  </Button>
                )}
              </div>
            </div>

            {isLoading ? (
              <Loader className="min-h-32" />
            ) : (
              <>
                <div className="relative">
                  <CodeMirror
                    onCreateEditor={(view) => {
                      editorViewRef.current = view;
                    }}
                    onFocus={onFocus}
                    onUpdate={handleEditorUpdate}
                    theme={codeMirrorPromptTheme}
                    value={localText}
                    onChange={handleContentChange}
                    placeholder="Type your message"
                    editable={!disabled}
                    basicSetup={{
                      foldGutter: false,
                      allowMultipleSelections: false,
                      lineNumbers: false,
                      highlightActiveLine: false,
                    }}
                    extensions={[
                      EditorView.lineWrapping,
                      mustachePlugin,
                      ...(braceKeyExtension ? [braceKeyExtension] : []),
                    ]}
                  />
                  {hasJsonData && (
                    <JsonTreePopover
                      data={jsonTreeData || {}}
                      onSelect={handleJsonPathSelect}
                      open={isJsonPopoverOpen}
                      onOpenChange={handlePopoverOpenChange}
                      align="start"
                      side="bottom"
                      searchQuery={jsonSearchQuery}
                      captureKeyboard={false}
                      trigger={
                        <span
                          ref={popoverTriggerRef}
                          className="pointer-events-none"
                          aria-hidden="true"
                          style={{
                            position: "absolute",
                            top: popoverPosition.top,
                            left: popoverPosition.left,
                            width: 1,
                            height: 1,
                          }}
                        />
                      }
                    />
                  )}
                </div>
                {!disableMedia && role === LLM_MESSAGE_ROLE.user && (
                  <PromptMessageMediaSection
                    images={images}
                    videos={videos}
                    audios={audios}
                    setImages={setImages}
                    setVideos={setVideos}
                    setAudios={setAudios}
                    promptVariables={promptVariables}
                    disabled={disabled}
                  />
                )}
              </>
            )}
          </CardContent>
        </Card>
        {errorText && <FormErrorSkeleton>{errorText}</FormErrorSkeleton>}
      </>
    );
  },
);

LLMPromptMessage.displayName = "LLMPromptMessage";

export default LLMPromptMessage;
