import React, {
  useRef,
  useState,
  useImperativeHandle,
  forwardRef,
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
import LLMPromptMessageActions, {
  ImprovePromptConfig,
} from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessageActions";
import AddMediaPopover from "@/v2/pages-shared/llm/PromptMessageMediaTags/AddMediaPopover";
import MediaTagsList from "@/v2/pages-shared/llm/PromptMessageMediaTags/MediaTagsList";
import PromptVariableEditor, {
  PromptVariableEditorHandle,
} from "@/v2/pages-shared/llm/PromptVariableEditor/PromptVariableEditor";

import { cn } from "@/lib/utils";
import {
  getTextFromMessageContent,
  hasAudiosInContent,
  hasImagesInContent,
  hasVideosInContent,
  isMediaAllowedForRole,
} from "@/lib/llm";
import { useMessageContent } from "@/hooks/useMessageContent";

import isEmpty from "lodash/isEmpty";

import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { DropdownOption } from "@/types/shared";
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
  focus: () => void;
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
  onFocus?: () => void;
  disableMedia?: boolean;
  promptVariables?: string[];
  improvePromptConfig?: ImprovePromptConfig;
  jsonTreeData?: JsonObject | null;
  onJsonPathSelect?: (path: string, value: JsonValue) => void;
  compact?: boolean;
  shiftEnterInsertsNewline?: boolean;
  size?: "default" | "xs";
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
      onFocus,
      onDuplicateMessage,
      onRemoveMessage,
      disableMedia = true,
      promptVariables,
      improvePromptConfig,
      jsonTreeData,
      onJsonPathSelect,
      compact = false,
      shiftEnterInsertsNewline = false,
      size = "default",
    },
    ref,
  ) => {
    const [isExpanded, setIsExpanded] = useState(false);
    const [isHoldActionsVisible, setIsHoldActionsVisible] = useState(false);
    const [isMediaPopoverOpen, setIsMediaPopoverOpen] = useState(false);
    const [isLoading, setIsLoading] = useState(false);
    const { id, role, content } = message;

    const { active, listeners, setNodeRef, transform, transition } =
      useSortable({ id });

    const editorRef = useRef<PromptVariableEditorHandle>(null);
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

    useImperativeHandle(ref, () => ({
      insertAtCursor: (text) => editorRef.current?.insertAtCursor(text),
      focus: () => editorRef.current?.focusAtEnd(),
    }));

    const hasJsonData = !isEmpty(jsonTreeData);

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

    const messagePlaceholder = "Type your message";
    const showMediaActions = !disableMedia && role === LLM_MESSAGE_ROLE.user;
    const hasMediaTags =
      images.length > 0 || videos.length > 0 || audios.length > 0;

    return (
      <>
        <Card
          key={id}
          data-testid="playground-message-row"
          data-role={role}
          style={style}
          ref={setNodeRef}
          onClick={() => {
            editorRef.current?.focus();
          }}
          className={cn(
            "group shadow-none [&:focus-within]:border-primary",
            size === "xs" ? "px-3 pb-3 pt-2" : "p-1 pb-1.5 pl-2",
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
                    "-ml-[7px] py-1 flex cursor-move items-center text-light-slate invisible [&>svg]:size-3.5",
                    !hideDragButton &&
                      "group-hover:visible [.group:focus-within_&]:visible",
                  )}
                  {...listeners}
                >
                  <GripVertical />
                </span>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button
                      variant="minimal"
                      size="2xs"
                      className="comet-body-xs min-w-4 p-0 text-muted-slate"
                    >
                      {LLM_MESSAGE_ROLE_NAME_MAP[role] || role}
                      <ChevronDown className="ml-1 size-3" />
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
                  "flex min-w-0 items-center invisible group-hover:visible [.group:focus-within_&]:visible",
                  (showAlwaysActionsPanel || isHoldActionsVisible) && "visible",
                )}
              >
                {!hidePromptActions && (
                  <LLMPromptMessageActions
                    message={message}
                    onChangeMessage={onChangeMessage}
                    setIsLoading={setIsLoading}
                    setIsHoldActionsVisible={setIsHoldActionsVisible}
                    improvePromptConfig={improvePromptConfig}
                  />
                )}
                <div className="flex shrink-0 items-center">
                  {!hidePromptActions && (
                    <Separator orientation="vertical" className="mx-1 h-4" />
                  )}
                  {!hideRemoveButton && (
                    <TooltipWrapper content="Remove message">
                      <Button
                        variant="minimal"
                        size="icon-2xs"
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
                      size="icon-2xs"
                      onClick={onDuplicateMessage}
                      type="button"
                    >
                      <CopyPlus />
                    </Button>
                  </TooltipWrapper>
                </div>
              </div>
            </div>

            {isLoading ? (
              <Loader className="min-h-32" />
            ) : (
              <div className="flex flex-col gap-1 pl-[7px] pr-1">
                <div
                  className="relative"
                  data-testid="playground-message-editor"
                >
                  <div
                    className={
                      compact && !isExpanded
                        ? "max-h-[1.5em] cursor-pointer overflow-hidden"
                        : undefined
                    }
                    onClick={
                      compact && !isExpanded
                        ? (e) => {
                            e.stopPropagation();
                            setIsExpanded(true);
                          }
                        : undefined
                    }
                  >
                    <PromptVariableEditor
                      ref={editorRef}
                      value={localText}
                      onChange={handleContentChange}
                      onFocus={onFocus}
                      placeholder={messagePlaceholder}
                      jsonTreeData={jsonTreeData}
                      onJsonPathSelect={onJsonPathSelect}
                      shiftEnterInsertsNewline={shiftEnterInsertsNewline}
                      size={size}
                    />
                  </div>
                  {compact && !isExpanded && localText && (
                    <Button
                      variant="link"
                      size="2xs"
                      onClick={(e) => {
                        e.stopPropagation();
                        setIsExpanded(true);
                      }}
                      className="absolute right-0 top-0 bg-background pl-5 text-muted-slate"
                      type="button"
                    >
                      Show more
                    </Button>
                  )}
                  {compact && isExpanded && (
                    <Button
                      variant="link"
                      size="2xs"
                      onClick={(e) => {
                        e.stopPropagation();
                        setIsExpanded(false);
                      }}
                      className="mt-1 p-0 text-muted-slate"
                      type="button"
                    >
                      Show less
                    </Button>
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
                        <Button variant="minimal" size="icon-2xs" type="button">
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
                        <Button variant="minimal" size="icon-2xs" type="button">
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
                        <Button variant="minimal" size="icon-2xs" type="button">
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
                      size="icon-2xs"
                      onMouseDown={(e) => e.preventDefault()}
                      onClick={(e) => {
                        e.stopPropagation();
                        editorRef.current?.triggerVariableSearch();
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
