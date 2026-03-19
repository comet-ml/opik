import React, {
  useEffect,
  useRef,
  useState,
  useImperativeHandle,
  forwardRef,
  useCallback,
} from "react";
import {
  Braces,
  ChevronDown,
  CopyPlus,
  GripVertical,
  Image,
  Music,
  Trash,
  Video,
} from "lucide-react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";

import { Button } from "@/ui/button";
import { FormErrorSkeleton } from "@/ui/form";
import { Card, CardContent } from "@/ui/card";
import { Separator } from "@/ui/separator";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import Loader from "@/shared/Loader/Loader";
import { JsonObject, JsonValue } from "@/types/shared";
import JsonTreePopover from "@/shared/JsonTreePopover/JsonTreePopover";
import LLMPromptMessageActions, {
  ImprovePromptConfig,
} from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessageActions";
import AddMediaPopover from "@/v2/pages-shared/llm/PromptMessageMediaTags/AddMediaPopover";
import MediaTagsList from "@/v2/pages-shared/llm/PromptMessageMediaTags/MediaTagsList";

import { cn } from "@/lib/utils";
import {
  getTextFromMessageContent,
  hasAudiosInContent,
  hasImagesInContent,
  hasVideosInContent,
  isMediaAllowedForRole,
} from "@/lib/llm";
import { useMessageContent } from "@/hooks/useMessageContent";
import { useJsonPopover } from "./useJsonPopover";

import isEmpty from "lodash/isEmpty";

import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { DropdownOption } from "@/types/shared";
import {
  mustachePlugin,
  codeMirrorPromptTheme,
  VariableHintExtension,
} from "@/constants/codeMirrorPlugins";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";

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
  jsonTreeData?: JsonObject | null;
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
      jsonTreeData,
      onJsonPathSelect,
    },
    ref,
  ) => {
    const [isHoldActionsVisible, setIsHoldActionsVisible] = useState(false);
    const [isMediaPopoverOpen, setIsMediaPopoverOpen] = useState(false);
    const [isLoading, setIsLoading] = useState(false);
    const [focusedVariableKey, setFocusedVariableKey] = useState<string | null>(
      null,
    );
    const { id, role, content } = message;

    const { active, listeners, setNodeRef, transform, transition } =
      useSortable({ id });

    const editorViewRef = useRef<EditorView | null>(null);
    const popoverTriggerRef = useRef<HTMLSpanElement | null>(null);
    const variableHintRef = useRef(new VariableHintExtension());
    const style = {
      transform: CSS.Translate.toString(transform),
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

    const hasJsonData = !isEmpty(jsonTreeData);

    const {
      isJsonPopoverOpen,
      jsonSearchQuery,
      popoverPosition,
      braceStartPos,
      handleJsonPathSelect,
      handlePopoverOpenChange,
      handleEditorUpdate,
      braceKeyExtension,
      triggerVariableSearch,
    } = useJsonPopover({
      editorViewRef,
      hasJsonData,
      insertTextAtCursor,
      onJsonPathSelect,
    });

    const hintText =
      isJsonPopoverOpen && !jsonSearchQuery
        ? focusedVariableKey || "Find variable"
        : null;

    // Sync the inline variable hint (ghost text after "{{") with the CodeMirror editor
    useEffect(() => {
      const view = editorViewRef.current;
      if (!view) return;
      variableHintRef.current.update(view, {
        text: hintText,
        pos: braceStartPos,
      });
    }, [hintText, braceStartPos]);

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

    const showMediaActions = !disableMedia && role === LLM_MESSAGE_ROLE.user;
    const hasMediaTags =
      images.length > 0 || videos.length > 0 || audios.length > 0;

    return (
      <>
        <Card
          key={id}
          style={style}
          ref={setNodeRef}
          onClick={() => {
            editorViewRef.current?.focus();
          }}
          className={cn(
            "group p-2 shadow-none [&:focus-within]:border-primary",
            {
              "pb-0": showMediaActions || hasJsonData,
              "z-10 shadow-sm": id === active?.id,
              "border-destructive": Boolean(errorText),
            },
          )}
        >
          <CardContent className="p-0">
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center">
                <span
                  className={cn(
                    "-ml-[7px] py-2 flex cursor-move items-center text-light-slate invisible [&>svg]:size-3.5",
                    !hideDragButton &&
                      "group-hover:visible [.group:focus-within_&]:visible",
                  )}
                  {...listeners}
                >
                  <GripVertical />
                </span>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="minimal" size="sm" className="min-w-4 p-0">
                      {LLM_MESSAGE_ROLE_NAME_MAP[role] || role}
                      <ChevronDown className="ml-1 w-4" />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent
                    align="start"
                    onCloseAutoFocus={(e) => e.preventDefault()}
                  >
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
              </div>
              <div
                className={cn(
                  "flex items-center invisible group-hover:visible [.group:focus-within_&]:visible",
                  (showAlwaysActionsPanel || isHoldActionsVisible) && "visible",
                )}
              >
                {!hidePromptActions && (
                  <>
                    <LLMPromptMessageActions
                      message={message}
                      onChangeMessage={onChangeMessage}
                      onReplaceWithChatPrompt={onReplaceWithChatPrompt}
                      onClearOtherPromptLinks={onClearOtherPromptLinks}
                      setIsLoading={setIsLoading}
                      setIsHoldActionsVisible={setIsHoldActionsVisible}
                      improvePromptConfig={improvePromptConfig}
                    />
                    <Separator orientation="vertical" className="mx-0.5 h-4" />
                  </>
                )}
                {!hideRemoveButton && (
                  <TooltipWrapper content="Remove message">
                    <Button
                      variant="minimal"
                      size="icon-sm"
                      onClick={onRemoveMessage}
                      type="button"
                    >
                      <Trash />
                    </Button>
                  </TooltipWrapper>
                )}
                <TooltipWrapper content="Duplicate message">
                  <Button
                    variant="minimal"
                    size="icon-sm"
                    onClick={onDuplicateMessage}
                    type="button"
                  >
                    <CopyPlus />
                  </Button>
                </TooltipWrapper>
              </div>
            </div>

            {isLoading ? (
              <Loader className="min-h-32" />
            ) : (
              <div className="flex flex-col gap-2 px-2">
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
                    editable
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
                      ...(hasJsonData
                        ? variableHintRef.current.getExtension()
                        : []),
                    ]}
                  />
                  {hasJsonData && (
                    <JsonTreePopover
                      data={jsonTreeData || {}}
                      onSelect={handleJsonPathSelect}
                      open={isJsonPopoverOpen}
                      onOpenChange={handlePopoverOpenChange}
                      searchQuery={jsonSearchQuery}
                      onFocusedPathChange={setFocusedVariableKey}
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

                {showMediaActions && hasMediaTags && (
                  <div className="flex min-h-7 flex-wrap items-center gap-1.5">
                    <MediaTagsList
                      type="image"
                      items={images}
                      setItems={setImages}
                    />
                    <MediaTagsList
                      type="video"
                      items={videos}
                      setItems={setVideos}
                    />
                    <MediaTagsList
                      type="audio"
                      items={audios}
                      setItems={setAudios}
                    />
                  </div>
                )}
              </div>
            )}

            {!isLoading && (showMediaActions || hasJsonData) && (
              <div
                className={cn(
                  "flex min-h-8 items-center invisible group-hover:visible [.group:focus-within_&]:visible",
                  (showAlwaysActionsPanel ||
                    isHoldActionsVisible ||
                    isMediaPopoverOpen) &&
                    "visible",
                )}
              >
                {showMediaActions && (
                  <>
                    <AddMediaPopover
                      type="image"
                      items={images}
                      setItems={setImages}
                      promptVariables={promptVariables}
                      onOpenChange={setIsMediaPopoverOpen}
                    >
                      <TooltipWrapper content="Add image">
                        <Button variant="minimal" size="icon-sm" type="button">
                          <Image />
                        </Button>
                      </TooltipWrapper>
                    </AddMediaPopover>
                    <AddMediaPopover
                      type="audio"
                      items={audios}
                      setItems={setAudios}
                      promptVariables={promptVariables}
                      onOpenChange={setIsMediaPopoverOpen}
                    >
                      <TooltipWrapper content="Add audio">
                        <Button variant="minimal" size="icon-sm" type="button">
                          <Music />
                        </Button>
                      </TooltipWrapper>
                    </AddMediaPopover>
                    <AddMediaPopover
                      type="video"
                      items={videos}
                      setItems={setVideos}
                      promptVariables={promptVariables}
                      onOpenChange={setIsMediaPopoverOpen}
                    >
                      <TooltipWrapper content="Add video">
                        <Button variant="minimal" size="icon-sm" type="button">
                          <Video />
                        </Button>
                      </TooltipWrapper>
                    </AddMediaPopover>
                  </>
                )}
                {showMediaActions && hasJsonData && (
                  <Separator orientation="vertical" className="mx-0.5 h-4" />
                )}
                {hasJsonData && (
                  <TooltipWrapper content="Type {{ or click here to add variable">
                    <Button
                      variant="minimal"
                      size="icon-sm"
                      onMouseDown={(e) => e.preventDefault()}
                      onClick={(e) => {
                        e.stopPropagation();
                        triggerVariableSearch();
                      }}
                      type="button"
                    >
                      <Braces />
                    </Button>
                  </TooltipWrapper>
                )}
              </div>
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
