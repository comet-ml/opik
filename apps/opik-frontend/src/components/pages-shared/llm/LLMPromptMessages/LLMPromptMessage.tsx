import React, {
  useRef,
  useState,
  useImperativeHandle,
  forwardRef,
} from "react";
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
    const { id, role, content } = message;

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
