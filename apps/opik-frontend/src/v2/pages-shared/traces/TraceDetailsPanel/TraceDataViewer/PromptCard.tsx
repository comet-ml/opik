import React, { useCallback, useMemo, useState } from "react";
import {
  ChevronDown,
  ChevronRight,
  Copy,
  ExternalLink,
  Play,
  Sparkles,
} from "lucide-react";
import { Link } from "@tanstack/react-router";
import copy from "clipboard-copy";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Separator } from "@/ui/separator";
import { Skeleton } from "@/ui/skeleton";
import { useToast } from "@/ui/use-toast";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import SyntaxHighlighter from "@/shared/SyntaxHighlighter/SyntaxHighlighter";
import PromptMessagesReadonly, {
  ChatMessage,
} from "@/v2/pages-shared/llm/PromptMessagesReadonly/PromptMessagesReadonly";
import { parseMessagesFromTemplate } from "@/v2/pages-shared/llm/PromptTemplateView/PromptTemplateView";
import StageTag from "@/v2/pages-shared/version-history/StageTag";
import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";
import { PromptLibraryMetadata } from "@/types/playground";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { pickHighestStage } from "@/utils/agent-configurations";
import useLoadPlayground from "@/v2/pages-shared/playground/useLoadPlayground";
import { parsePromptVersionContent } from "@/lib/llm";
import { useActiveProjectId } from "@/store/AppStore";
import { usePermissions } from "@/contexts/PermissionsContext";
import { cn } from "@/lib/utils";

type PromptView = "pretty" | "raw";

type PromptCardProps = {
  rawPrompt: PromptLibraryMetadata;
  workspaceName: string;
  search?: string;
  defaultOpen?: boolean;
};

const normalizeTemplate = (template: unknown): string => {
  if (typeof template === "string") return template;
  if (template === null || template === undefined) return "";
  return JSON.stringify(template, null, 2);
};

const PromptCard: React.FC<PromptCardProps> = ({
  rawPrompt,
  workspaceName,
  search,
  defaultOpen = false,
}) => {
  const [isOpen, setIsOpen] = useState(defaultOpen);
  const [view, setView] = useState<PromptView>("pretty");
  const [showLoadConfirm, setShowLoadConfirm] = useState(false);

  const { toast } = useToast();
  const activeProjectId = useActiveProjectId();
  const {
    permissions: { canUsePlayground },
  } = usePermissions();
  const { loadPlayground, isPlaygroundEmpty, isPendingProviderKeys } =
    useLoadPlayground();

  const promptName = rawPrompt.name;
  const promptId = rawPrompt.id;
  const versionId = rawPrompt.version.id;
  const template = rawPrompt.version.template;

  const { data: versionsData, isLoading: isVersionsLoading } =
    usePromptVersionsById(
      {
        promptId,
        page: 1,
        size: 100,
        sorting: [{ id: "created_at", desc: true }],
      },
      {
        enabled: Boolean(promptId),
        staleTime: 60_000,
      },
    );

  const { versionLabel, stage } = useMemo(() => {
    const versions = versionsData?.content ?? [];
    const total = versionsData?.total ?? versions.length;
    const idx = versions.findIndex((v) => v.id === versionId);
    if (idx === -1) {
      const fallback = rawPrompt.version.commit
        ? rawPrompt.version.commit.slice(0, 7)
        : undefined;
      return { versionLabel: fallback, stage: undefined };
    }
    return {
      versionLabel: `v${total - idx}`,
      stage: pickHighestStage(versions[idx].tags),
    };
  }, [versionsData, versionId, rawPrompt.version.commit]);

  const messages = useMemo<ChatMessage[]>(
    () => parseMessagesFromTemplate(template),
    [template],
  );
  const hasMessages = messages.length > 0;
  const isChatPrompt =
    rawPrompt.template_structure === PROMPT_TEMPLATE_STRUCTURE.CHAT ||
    (rawPrompt.template_structure === undefined && hasMessages);

  const parsedTemplate = useMemo(() => {
    try {
      return typeof template === "string" ? JSON.parse(template) : template;
    } catch {
      return template;
    }
  }, [template]);

  const textContent = useMemo(() => normalizeTemplate(template), [template]);

  const handleCopy = useCallback(async () => {
    await copy(textContent);
    toast({ description: "Prompt copied to clipboard" });
  }, [textContent, toast]);

  const doLoadIntoPlayground = useCallback(() => {
    loadPlayground({
      promptContent: parsePromptVersionContent({
        template: normalizeTemplate(template),
        metadata: (rawPrompt.version.metadata as object) ?? {},
      }),
      promptId,
      promptVersionId: versionId,
      templateStructure: rawPrompt.template_structure,
    });
  }, [loadPlayground, template, rawPrompt, promptId, versionId]);

  const handleOpenInPlayground = useCallback(() => {
    if (isPlaygroundEmpty) {
      doLoadIntoPlayground();
    } else {
      setShowLoadConfirm(true);
    }
  }, [isPlaygroundEmpty, doLoadIntoPlayground]);

  const renderContent = () => {
    if (view === "raw") {
      return (
        <SyntaxHighlighter
          withSearch={Boolean(search)}
          data={parsedTemplate as object}
          search={search}
        />
      );
    }
    if (isChatPrompt && hasMessages) {
      return <PromptMessagesReadonly messages={messages} />;
    }
    return (
      <div
        className="comet-body-s whitespace-pre-wrap break-words text-foreground"
        data-testid="prompt-text-content"
      >
        {textContent}
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
            promptId,
          }}
          search={{ activeVersionId: versionId }}
          className="comet-body-s-accented truncate text-foreground underline-offset-2 hover:underline"
        >
          {promptName}
        </Link>
        {versionLabel ? (
          <span className="comet-body-xs inline-flex shrink-0 items-center gap-1 text-light-slate">
            {stage ? (
              <StageTag value={stage} size="xs" />
            ) : (
              <Sparkles className="size-3 text-[var(--tag-lime-text)]" />
            )}
            {versionLabel}
          </span>
        ) : isVersionsLoading ? (
          <Skeleton className="h-3 w-8 shrink-0" />
        ) : null}

        <div className="ml-auto flex shrink-0 items-center gap-1">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="2xs">
                {view === "pretty" ? (
                  <>
                    Pretty <Sparkles className="ml-1 size-3" />
                  </>
                ) : (
                  <>Raw</>
                )}
                <ChevronDown className="ml-1 size-3" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem
                onClick={() => setView("pretty")}
                selected={view === "pretty"}
              >
                Pretty
                <Sparkles className="ml-1 size-3" />
              </DropdownMenuItem>
              <DropdownMenuItem
                onClick={() => setView("raw")}
                selected={view === "raw"}
              >
                Raw
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>

          <Separator orientation="vertical" className="h-4" />

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="2xs">
                Use prompt
                <ChevronDown className="ml-1 size-3" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              {canUsePlayground && (
                <DropdownMenuItem
                  disabled={isPendingProviderKeys}
                  onClick={handleOpenInPlayground}
                >
                  <Play className="mr-2 size-3.5" />
                  Open in playground
                </DropdownMenuItem>
              )}
              <DropdownMenuItem asChild>
                <Link
                  to="/$workspaceName/projects/$projectId/prompts/$promptId"
                  params={{
                    workspaceName,
                    projectId: activeProjectId!,
                    promptId,
                  }}
                  search={{ activeVersionId: versionId }}
                >
                  <ExternalLink className="mr-2 size-3.5" />
                  View in library
                </Link>
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>

          <Separator orientation="vertical" className="h-4" />

          <TooltipWrapper content="Copy prompt">
            <Button variant="minimal" size="icon-2xs" onClick={handleCopy}>
              <Copy />
            </Button>
          </TooltipWrapper>
        </div>
      </div>

      <div className={cn("px-3 pb-3", !isOpen && "hidden")}>
        {renderContent()}
      </div>

      <ConfirmDialog
        open={showLoadConfirm}
        setOpen={setShowLoadConfirm}
        onConfirm={doLoadIntoPlayground}
        title="Load prompt"
        description="Loading this prompt into the Playground will replace any unsaved changes. This action cannot be undone."
        confirmText="Load prompt"
      />
    </div>
  );
};

export default PromptCard;
