import React, {
  useRef,
  useState,
  useImperativeHandle,
  forwardRef,
} from "react";
import {
  ChevronDown,
  CopyPlus,
  GripHorizontal,
  Trash,
  Info,
  X,
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

import { LLM_MESSAGE_ROLE, LLMMessage, MessageSourceType } from "@/types/llm";

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

// Source annotation label and color mapping
const SOURCE_ANNOTATION_CONFIG: Record<
  MessageSourceType,
  { label: string; color: string; icon: string }
> = {
  system_config: {
    label: "System",
    color: "bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400",
    icon: "⚙️",
  },
  user_input: {
    label: "User Input",
    color: "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400",
    icon: "👤",
  },
  tool_output: {
    label: "Tool Output",
    color: "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400",
    icon: "🔧",
  },
  llm_response: {
    label: "LLM Response",
    color: "bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400",
    icon: "🤖",
  },
  merged: {
    label: "Merged",
    color: "bg-indigo-100 text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-400",
    icon: "🔀",
  },
  trace_input: {
    label: "Trace Input",
    color: "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-400",
    icon: "📍",
  },
};

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
    },
    ref,
  ) => {
    const [isHoldActionsVisible, setIsHoldActionsVisible] = useState(false);
    const [isLoading, setIsLoading] = useState(false);
    const [showSourceAnnotation, setShowSourceAnnotation] = useState(true);
    const { id, role, content, sourceAnnotation } = message;

    const { active, attributes, listeners, setNodeRef, transform, transition } =
      useSortable({ id });

    const editorViewRef = useRef<EditorView | null>(null);
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

    useImperativeHandle(ref, () => ({
      insertAtCursor: (text: string) => {
        const view = editorViewRef.current;
        if (view) {
          const cursorPos = view.state.selection.main.head;
          view.dispatch({
            changes: { from: cursorPos, insert: text },
            selection: { anchor: cursorPos + text.length },
          });
          view.focus();
        }
      },
    }));

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
                <CodeMirror
                  onCreateEditor={(view) => {
                    editorViewRef.current = view;
                  }}
                  onFocus={onFocus}
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
                  extensions={[EditorView.lineWrapping, mustachePlugin]}
                />
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
                {/* Source Annotation Badge */}
                {sourceAnnotation && showSourceAnnotation && (
                  <div className="mt-2 flex items-center gap-1.5">
                    <TooltipWrapper
                      content={
                        sourceAnnotation.description ||
                        `Source: ${sourceAnnotation.type}`
                      }
                      side="top"
                    >
                      <div
                        className={cn(
                          "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-medium",
                          SOURCE_ANNOTATION_CONFIG[sourceAnnotation.type]
                            ?.color || SOURCE_ANNOTATION_CONFIG.trace_input.color,
                        )}
                      >
                        <span>
                          {SOURCE_ANNOTATION_CONFIG[sourceAnnotation.type]
                            ?.icon || "📍"}
                        </span>
                        <span>
                          {sourceAnnotation.sourceSpanName
                            ? `From: ${sourceAnnotation.sourceSpanName}`
                            : SOURCE_ANNOTATION_CONFIG[sourceAnnotation.type]
                                ?.label || "Trace Input"}
                        </span>
                        <Info className="size-3 opacity-60" />
                      </div>
                    </TooltipWrapper>
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        setShowSourceAnnotation(false);
                      }}
                      className="rounded p-0.5 text-muted-foreground opacity-60 transition-opacity hover:bg-muted hover:opacity-100"
                      title="Hide source annotation"
                    >
                      <X className="size-3" />
                    </button>
                  </div>
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
