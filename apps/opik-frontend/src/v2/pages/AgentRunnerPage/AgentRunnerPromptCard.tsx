import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  ChevronDown,
  ChevronRight,
  Clock,
  Save,
  Sparkles,
} from "lucide-react";
import { Link } from "@tanstack/react-router";

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
import StageTag from "@/v2/pages-shared/version-history/StageTag";
import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import AutoResizeTextarea from "@/v2/pages-shared/agent-configuration/fields/AutoResizeTextarea";
import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";
import useCreatePromptVersionMutation from "@/api/prompts/useCreatePromptVersionMutation";
import { Prompt, PROMPT_TEMPLATE_STRUCTURE, PromptVersion } from "@/types/prompts";
import { LLMMessage } from "@/types/llm";
import {
  generateDefaultLLMPromptMessage,
  getNextMessageType,
  parseChatTemplateToLLMMessages,
} from "@/lib/llm";
import {
  chatTemplatesEqual,
  serializeChatTemplate,
} from "@/lib/chatTemplate";
import { pickHighestStage } from "@/utils/agent-configurations";
import { formatDate, getTimeFromNow } from "@/lib/date";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { cn } from "@/lib/utils";

type AgentRunnerPromptCardProps = {
  prompt: Prompt;
};

type LoadedVersion = {
  version: PromptVersion;
  label: string;
};

const AgentRunnerPromptCard: React.FC<AgentRunnerPromptCardProps> = ({
  prompt,
}) => {
  const workspaceName = useAppStore((s) => s.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const { toast } = useToast();

  const [isOpen, setIsOpen] = useState(true);
  const [pickedVersionId, setPickedVersionId] = useState<string | null>(null);
  const [draftTemplate, setDraftTemplate] = useState<string>("");
  const [draftMessages, setDraftMessages] = useState<LLMMessage[]>([]);

  const isChatPrompt =
    prompt.template_structure === PROMPT_TEMPLATE_STRUCTURE.CHAT;

  const { data: versionsData, isLoading: isVersionsLoading } =
    usePromptVersionsById(
      {
        promptId: prompt.id,
        page: 1,
        size: 100,
        sorting: [{ id: "created_at", desc: true }],
      },
      { enabled: isOpen, staleTime: 60_000 },
    );

  const versions = versionsData?.content ?? [];
  const total = versionsData?.total ?? versions.length;

  const selectedVersionId =
    pickedVersionId ?? prompt.latest_version?.id ?? versions[0]?.id ?? "";

  const selectedVersion = useMemo<LoadedVersion | undefined>(() => {
    const idx = versions.findIndex((v) => v.id === selectedVersionId);
    if (idx === -1) return undefined;
    return { version: versions[idx], label: `v${total - idx}` };
  }, [versions, total, selectedVersionId]);

  const baselineTemplate = selectedVersion?.version.template ?? "";
  const loadedVersionIdRef = useRef<string | null>(null);

  useEffect(() => {
    if (!selectedVersion) return;
    if (loadedVersionIdRef.current === selectedVersion.version.id) return;
    loadedVersionIdRef.current = selectedVersion.version.id;
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
  }, [selectedVersion, isChatPrompt]);

  const hasUnsavedChanges = useMemo(() => {
    if (!selectedVersion) return false;
    if (isChatPrompt) {
      return !chatTemplatesEqual(
        serializeChatTemplate(draftMessages),
        baselineTemplate || "[]",
      );
    }
    return draftTemplate !== baselineTemplate;
  }, [
    selectedVersion,
    isChatPrompt,
    draftMessages,
    draftTemplate,
    baselineTemplate,
  ]);

  const { mutateAsync: createVersionAsync, isPending: isSaving } =
    useCreatePromptVersionMutation();

  const handleSave = useCallback(async () => {
    if (!hasUnsavedChanges) return;
    const template = isChatPrompt
      ? serializeChatTemplate(draftMessages)
      : draftTemplate;
    try {
      await createVersionAsync({
        name: prompt.name,
        template,
        templateStructure: prompt.template_structure,
        type: selectedVersion?.version.type,
        projectId: activeProjectId ?? undefined,
        onSuccess: (v) => setPickedVersionId(v.id),
      });
      toast({
        description: `Saved new version of ${prompt.name}`,
      });
    } catch {
      // toast handled in mutation
    }
  }, [
    hasUnsavedChanges,
    createVersionAsync,
    prompt.name,
    prompt.template_structure,
    isChatPrompt,
    draftMessages,
    draftTemplate,
    selectedVersion?.version.type,
    activeProjectId,
    toast,
  ]);

  const handleAddMessage = useCallback(() => {
    setDraftMessages((prev) => {
      const last = prev[prev.length - 1];
      const nextRole = last ? getNextMessageType(last) : undefined;
      return [...prev, generateDefaultLLMPromptMessage({ role: nextRole })];
    });
  }, []);

  const selectedStage = pickHighestStage(selectedVersion?.version.tags);

  const renderBody = () => {
    if (!selectedVersion && !loadedVersionIdRef.current) {
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
      <div className="flex items-center gap-2 px-3 py-2">
        <button
          type="button"
          aria-expanded={isOpen}
          onClick={() => setIsOpen((p) => !p)}
          className="flex shrink-0 items-center text-light-slate hover:text-foreground"
        >
          {isOpen ? (
            <ChevronDown className="size-4" />
          ) : (
            <ChevronRight className="size-4" />
          )}
        </button>
        <Link
          to="/$workspaceName/projects/$projectId/prompts/$promptId"
          params={{
            workspaceName,
            projectId: activeProjectId!,
            promptId: prompt.id,
          }}
          search={{ activeVersionId: selectedVersionId }}
          className="comet-body-s-accented truncate text-foreground underline-offset-2 hover:underline"
        >
          {prompt.name}
        </Link>

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              size="2xs"
              className="comet-body-xs gap-1 text-light-slate"
              disabled={isVersionsLoading || versions.length === 0}
            >
              {selectedStage ? (
                <StageTag value={selectedStage} size="xs" />
              ) : (
                <Sparkles className="size-3 text-[var(--tag-lime-text)]" />
              )}
              {selectedVersion?.label ?? "v?"}
              <ChevronDown className="size-3" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-[220px]">
            {versions.map((version, idx) => {
              const label = `v${total - idx}`;
              const stage = pickHighestStage(version.tags);
              const isActive = version.id === selectedVersionId;
              return (
                <DropdownMenuItem
                  key={version.id}
                  selected={isActive}
                  onClick={() => setPickedVersionId(version.id)}
                  className="flex items-center gap-2"
                >
                  <span className="comet-body-s-accented">{label}</span>
                  {stage && <StageTag value={stage} size="xs" />}
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
          <div className="ml-auto flex items-center gap-2">
            <span className="comet-body-xs flex items-center gap-1 text-muted-slate">
              <span className="size-1.5 rounded-full bg-destructive" />
              Unsaved changes
            </span>
            <TooltipWrapper content="Save as new version">
              <Button
                variant="minimal"
                size="icon-2xs"
                disabled={isSaving}
                onClick={handleSave}
              >
                <Save />
              </Button>
            </TooltipWrapper>
          </div>
        )}
      </div>

      <div className={cn("px-3 pb-3", !isOpen && "hidden")}>{renderBody()}</div>
    </div>
  );
};

export default AgentRunnerPromptCard;
