import React, {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useState,
} from "react";
import {
  ChevronDown,
  ChevronRight,
  Clock,
  GitCommitVertical,
  Save,
} from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Skeleton } from "@/ui/skeleton";
import { useToast } from "@/ui/use-toast";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import EnvironmentBadgeList from "@/shared/EnvironmentLabel/EnvironmentBadgeList";
import StageTag from "@/v2/pages-shared/version-history/StageTag";
import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import AutoResizeTextarea from "@/v2/pages-shared/agent-configuration/fields/AutoResizeTextarea";
import SaveVersionDialog from "@/v2/pages-shared/llm/SaveVersionDialog/SaveVersionDialog";
import usePromptVersionsWithLabels from "@/v2/pages-shared/version-history/usePromptVersionsWithLabels";
import useCreatePromptVersionMutation from "@/api/prompts/useCreatePromptVersionMutation";
import {
  Prompt,
  PROMPT_TEMPLATE_STRUCTURE,
  PROMPT_VERSION_TYPE,
  PromptVersion,
} from "@/types/prompts";
import { LLMMessage } from "@/types/llm";
import {
  generateDefaultLLMPromptMessage,
  getNextMessageType,
  parseChatTemplateToLLMMessages,
} from "@/lib/llm";
import { chatTemplatesEqual, serializeChatTemplate } from "@/lib/chatTemplate";
import { formatDate, getTimeFromNow } from "@/lib/date";
import { useActiveProjectId } from "@/store/AppStore";
import { usePermissions } from "@/contexts/PermissionsContext";
import { cn } from "@/lib/utils";
import {
  PROMPT_SAVE_NEW_VERSION_TOOLTIP,
  PROMPT_UNSAVED_CHANGES_LABEL,
} from "@/constants/prompts";

type AgentRunnerPromptCardProps = {
  prompt: Prompt;
};

type LoadedVersion = {
  version: PromptVersion;
  label: string;
};

export type PromptMaskEntry = {
  promptId: string;
  versionId: string;
};

export type AgentRunnerPromptCardHandle = {
  prepareMask: () => Promise<PromptMaskEntry | null>;
};

const AgentRunnerPromptCard = forwardRef<
  AgentRunnerPromptCardHandle,
  AgentRunnerPromptCardProps
>(({ prompt }, ref) => {
  const activeProjectId = useActiveProjectId();
  const { toast } = useToast();
  const {
    permissions: { canCreatePrompts },
  } = usePermissions();

  const [isOpen, setIsOpen] = useState(true);
  const [pickedVersionId, setPickedVersionId] = useState<string | null>(null);
  const [draftTemplate, setDraftTemplate] = useState<string>("");
  const [draftMessages, setDraftMessages] = useState<LLMMessage[]>([]);
  const [isSaveDialogOpen, setIsSaveDialogOpen] = useState(false);

  const isChatPrompt =
    prompt.template_structure === PROMPT_TEMPLATE_STRUCTURE.CHAT;

  const {
    versions,
    descriptors,
    isLoading: isVersionsLoading,
    getDescriptor,
  } = usePromptVersionsWithLabels(prompt.id, { enabled: isOpen });

  const selectedVersionId =
    pickedVersionId ?? prompt.latest_version?.id ?? versions[0]?.id ?? "";

  const selectedVersion = useMemo<LoadedVersion | undefined>(() => {
    const descriptor = getDescriptor(selectedVersionId);
    if (!descriptor) return undefined;
    return { version: descriptor.version, label: descriptor.label };
  }, [getDescriptor, selectedVersionId]);

  const baselineTemplate = selectedVersion?.version.template ?? "";
  const [loadedVersionId, setLoadedVersionId] = useState<string | null>(null);

  useEffect(() => {
    if (!selectedVersion) return;
    if (loadedVersionId === selectedVersion.version.id) return;
    setLoadedVersionId(selectedVersion.version.id);
    if (isChatPrompt) {
      const messages = parseChatTemplateToLLMMessages(
        selectedVersion.version.template,
        { useTimestamp: true },
      );
      setDraftMessages(
        messages.length > 0 ? messages : [generateDefaultLLMPromptMessage()],
      );
      setDraftTemplate("");
    } else {
      setDraftTemplate(selectedVersion.version.template ?? "");
      setDraftMessages([]);
    }
  }, [selectedVersion, isChatPrompt, loadedVersionId]);

  const hasUnsavedChanges = useMemo(() => {
    if (!selectedVersion) return false;
    if (loadedVersionId !== selectedVersion.version.id) return false;
    if (isChatPrompt) {
      return !chatTemplatesEqual(
        serializeChatTemplate(draftMessages),
        baselineTemplate || "[]",
      );
    }
    return draftTemplate !== baselineTemplate;
  }, [
    selectedVersion,
    loadedVersionId,
    isChatPrompt,
    draftMessages,
    draftTemplate,
    baselineTemplate,
  ]);

  const { mutateAsync: createVersionAsync, isPending: isSaving } =
    useCreatePromptVersionMutation();

  const handleSave = useCallback(
    async (changeDescription: string) => {
      if (!hasUnsavedChanges || !canCreatePrompts) return;
      const template = isChatPrompt
        ? serializeChatTemplate(draftMessages)
        : draftTemplate;
      try {
        await createVersionAsync({
          name: prompt.name,
          template,
          changeDescription,
          metadata: selectedVersion?.version.metadata,
          templateStructure: prompt.template_structure,
          type: selectedVersion?.version.type,
          projectId: activeProjectId ?? undefined,
          onSuccess: (v) => setPickedVersionId(v.id),
        });
        toast({
          description: `Saved new version of ${prompt.name}`,
        });
        setIsSaveDialogOpen(false);
      } catch {
        // toast handled in mutation
      }
    },
    [
      hasUnsavedChanges,
      canCreatePrompts,
      createVersionAsync,
      prompt.name,
      prompt.template_structure,
      isChatPrompt,
      draftMessages,
      draftTemplate,
      selectedVersion?.version.type,
      selectedVersion?.version.metadata,
      activeProjectId,
      toast,
    ],
  );

  useImperativeHandle(
    ref,
    () => ({
      prepareMask: async () => {
        if (hasUnsavedChanges) {
          if (!canCreatePrompts) {
            toast({
              title: "Cannot save prompt changes",
              description: `Missing permission to create new versions of ${prompt.name}`,
              variant: "destructive",
            });
            throw new Error("Missing permission to save prompt version");
          }
          const template = isChatPrompt
            ? serializeChatTemplate(draftMessages)
            : draftTemplate;
          let createdVersionId: string | undefined;
          await createVersionAsync({
            name: prompt.name,
            template,
            metadata: selectedVersion?.version.metadata,
            templateStructure: prompt.template_structure,
            type: selectedVersion?.version.type,
            versionType: PROMPT_VERSION_TYPE.MASK,
            projectId: activeProjectId ?? undefined,
            onSuccess: (v) => {
              createdVersionId = v.id;
            },
          });
          if (!createdVersionId) {
            throw new Error("Failed to create prompt version");
          }
          return { promptId: prompt.id, versionId: createdVersionId };
        }

        if (pickedVersionId) {
          return { promptId: prompt.id, versionId: pickedVersionId };
        }

        return null;
      },
    }),
    [
      hasUnsavedChanges,
      pickedVersionId,
      canCreatePrompts,
      isChatPrompt,
      draftMessages,
      draftTemplate,
      createVersionAsync,
      prompt.id,
      prompt.name,
      prompt.template_structure,
      selectedVersion?.version.type,
      selectedVersion?.version.metadata,
      activeProjectId,
      toast,
    ],
  );

  const handleAddMessage = useCallback(() => {
    setDraftMessages((prev) => {
      const last = prev[prev.length - 1];
      const nextRole = last ? getNextMessageType(last) : undefined;
      return [...prev, generateDefaultLLMPromptMessage({ role: nextRole })];
    });
  }, []);

  const selectedStage = getDescriptor(selectedVersionId)?.stage;

  const renderBody = () => {
    if (!selectedVersion && !loadedVersionId) {
      return (
        <div className="space-y-2">
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
        </div>
      );
    }
    if (isChatPrompt) {
      return (
        <LLMPromptMessages
          messages={draftMessages}
          onChange={setDraftMessages}
          onAddMessage={handleAddMessage}
          hidePromptActions
          disableMedia
        />
      );
    }
    return (
      <div className="rounded-md border bg-background px-3 py-2">
        <AutoResizeTextarea
          value={draftTemplate}
          onChange={setDraftTemplate}
          placeholder="Prompt template"
        />
      </div>
    );
  };

  return (
    <div className="overflow-hidden rounded-md border border-border bg-soft-background">
      <div className="flex items-center gap-1.5 px-2 py-1">
        <button
          type="button"
          aria-expanded={isOpen}
          onClick={() => setIsOpen((p) => !p)}
          className="flex shrink-0 items-center text-light-slate hover:text-foreground"
        >
          {isOpen ? (
            <ChevronDown className="size-3" />
          ) : (
            <ChevronRight className="size-3" />
          )}
        </button>
        <span className="comet-body-xs truncate text-muted-slate">
          {prompt.name}
        </span>

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="minimal"
              size="2xs"
              className="comet-body-xs gap-1 px-1 text-muted-slate"
              disabled={isVersionsLoading || versions.length === 0}
            >
              {selectedStage ? (
                <StageTag value={selectedStage} size="xs" />
              ) : (
                <GitCommitVertical className="size-3 text-muted-slate" />
              )}
              {selectedVersion?.label ?? "v?"}
              <EnvironmentBadgeList
                names={selectedVersion?.version.environments}
                size="sm"
                withOverflow
                compact
                maxWidth={60}
              />
              <ChevronDown className="size-3" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent
            align="start"
            className="max-h-[40vh] w-[220px] overflow-y-auto"
          >
            {descriptors.map(({ version, label, stage }) => {
              const isActive = version.id === selectedVersionId;
              return (
                <DropdownMenuItem
                  key={version.id}
                  selected={isActive}
                  onClick={() => setPickedVersionId(version.id)}
                  className="flex items-center gap-2"
                >
                  <span className="comet-body-s">{label}</span>
                  {stage && <StageTag value={stage} size="xs" />}
                  <EnvironmentBadgeList
                    names={version.environments}
                    size="sm"
                    withOverflow
                    compact
                    maxWidth={60}
                  />
                  <TooltipWrapper
                    content={`${formatDate(version.created_at, {
                      utc: true,
                      includeSeconds: true,
                    })} UTC`}
                  >
                    <span className="comet-body-xs ml-auto flex items-center gap-1 text-light-slate">
                      <Clock className="size-3" />
                      {getTimeFromNow(version.created_at)}
                    </span>
                  </TooltipWrapper>
                </DropdownMenuItem>
              );
            })}
          </DropdownMenuContent>
        </DropdownMenu>

        {hasUnsavedChanges && (
          <div className="ml-auto flex items-center gap-1">
            <span className="comet-body-xs flex items-center gap-1 text-light-slate">
              <span className="size-1.5 rounded-full bg-destructive" />
              {PROMPT_UNSAVED_CHANGES_LABEL}
            </span>
            {canCreatePrompts && (
              <TooltipWrapper content={PROMPT_SAVE_NEW_VERSION_TOOLTIP}>
                <Button
                  variant="minimal"
                  size="icon-2xs"
                  disabled={isSaving}
                  onClick={() => setIsSaveDialogOpen(true)}
                >
                  <Save />
                </Button>
              </TooltipWrapper>
            )}
          </div>
        )}
      </div>

      <div className={cn("px-2 pb-2", !isOpen && "hidden")}>{renderBody()}</div>

      <SaveVersionDialog
        open={isSaveDialogOpen}
        setOpen={setIsSaveDialogOpen}
        promptName={prompt.name}
        isSaving={isSaving}
        onSave={handleSave}
      />
    </div>
  );
});

AgentRunnerPromptCard.displayName = "AgentRunnerPromptCard";

export default AgentRunnerPromptCard;
