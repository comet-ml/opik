import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  ArrowUpRight,
  ChevronDown,
  ChevronRight,
  Copy,
  GitCommitVertical,
  Play,
  Search,
  Sparkles,
  X,
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
import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import EnvironmentBadgeList from "@/shared/EnvironmentLabel/EnvironmentBadgeList";
import { useSyntaxHighlighterCode } from "@/shared/SyntaxHighlighter/hooks/useSyntaxHighlighterHooks";
import { MODE_TYPE } from "@/shared/SyntaxHighlighter/constants";
import CodeBlockBody from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/CodeBlock/CodeBlockBody";
import PromptMessagesReadonly, {
  ChatMessage,
} from "@/v2/pages-shared/llm/PromptMessagesReadonly/PromptMessagesReadonly";
import { parseMessagesFromTemplate } from "@/v2/pages-shared/llm/PromptTemplateView/PromptTemplateView";
import StageTag from "@/v2/pages-shared/version-history/StageTag";
import usePromptVersionsWithLabels from "@/v2/pages-shared/version-history/usePromptVersionsWithLabels";
import { PromptLibraryMetadata } from "@/types/playground";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
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
  const [localSearch, setLocalSearch] = useState("");
  const [isSearchExpanded, setIsSearchExpanded] = useState(false);
  const searchInputRef = useRef<HTMLInputElement>(null);

  const effectiveSearch = localSearch || search;

  useEffect(() => {
    if (isSearchExpanded) {
      searchInputRef.current?.focus();
    }
  }, [isSearchExpanded]);

  useEffect(() => {
    if (view !== "raw") {
      setLocalSearch("");
      setIsSearchExpanded(false);
    }
  }, [view]);

  const closeSearch = useCallback(() => {
    setLocalSearch("");
    setIsSearchExpanded(false);
  }, []);

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

  const { getDescriptor, isLoading: isVersionsLoading } =
    usePromptVersionsWithLabels(promptId);

  const { versionLabel, stage, environments } = useMemo(() => {
    const descriptor = getDescriptor(versionId);
    if (!descriptor) {
      const fallback = rawPrompt.version.commit
        ? rawPrompt.version.commit.slice(0, 7)
        : undefined;
      return {
        versionLabel: fallback,
        stage: undefined,
        environments: undefined,
      };
    }
    return {
      versionLabel: descriptor.label,
      stage: descriptor.stage,
      environments: descriptor.version.environments,
    };
  }, [getDescriptor, versionId, rawPrompt.version.commit]);

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

  const effectiveTemplateStructure = isChatPrompt
    ? PROMPT_TEMPLATE_STRUCTURE.CHAT
    : PROMPT_TEMPLATE_STRUCTURE.TEXT;

  const doLoadIntoPlayground = useCallback(() => {
    loadPlayground({
      promptContent: parsePromptVersionContent({
        template: normalizeTemplate(template),
        metadata: (rawPrompt.version.metadata as object) ?? {},
      }),
      promptId,
      promptVersionId: versionId,
      templateStructure: effectiveTemplateStructure,
    });
  }, [
    loadPlayground,
    template,
    rawPrompt,
    promptId,
    versionId,
    effectiveTemplateStructure,
  ]);

  const handleOpenInPlayground = useCallback(() => {
    if (isPlaygroundEmpty) {
      doLoadIntoPlayground();
    } else {
      setShowLoadConfirm(true);
    }
  }, [isPlaygroundEmpty, doLoadIntoPlayground]);

  const rawCodeOutput = useSyntaxHighlighterCode(
    parsedTemplate as object,
    MODE_TYPE.yaml,
  );

  const renderContent = () => {
    if (view === "raw") {
      return (
        <CodeBlockBody code={rawCodeOutput} searchValue={effectiveSearch} />
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
      <div
        className={cn(
          "flex items-center gap-1.5 bg-muted/50 px-2 py-1",
          isOpen && "border-b border-border",
        )}
      >
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
        <span className="comet-body-xs truncate text-light-slate">
          {promptName}
        </span>
        {versionLabel ? (
          <span className="comet-body-xs inline-flex shrink-0 items-center gap-1 text-light-slate">
            {stage ? (
              <StageTag value={stage} size="xs" />
            ) : (
              <GitCommitVertical className="size-3 text-light-slate" />
            )}
            {versionLabel}
          </span>
        ) : isVersionsLoading ? (
          <Skeleton className="h-3 w-8 shrink-0" />
        ) : null}
        {rawPrompt.modified && (
          <TooltipWrapper content="This run was edited after loading from the linked library version">
            <span className="comet-body-xs shrink-0 rounded bg-muted px-1.5 text-light-slate">
              modified
            </span>
          </TooltipWrapper>
        )}
        <EnvironmentBadgeList
          names={environments}
          size="sm"
          withOverflow
          compact
          maxWidth={60}
        />

        <div className="ml-auto flex shrink-0 items-center gap-1">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="ghost"
                size="2xs"
                className="text-light-slate hover:text-foreground"
              >
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

          <Button
            variant="ghost"
            size="2xs"
            asChild
            className="text-light-slate hover:text-foreground"
          >
            <Link
              to="/$workspaceName/projects/$projectId/prompts/$promptId"
              params={{
                workspaceName,
                projectId: activeProjectId!,
                promptId,
              }}
              search={versionId ? { activeVersionId: versionId } : {}}
            >
              View
              <ArrowUpRight className="ml-1 size-3" />
            </Link>
          </Button>

          {canUsePlayground && (
            <>
              <Separator orientation="vertical" className="h-4" />

              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button
                    variant="ghost"
                    size="2xs"
                    className="text-light-slate hover:text-foreground"
                  >
                    Use
                    <ChevronDown className="ml-1 size-3" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  <DropdownMenuItem
                    disabled={isPendingProviderKeys}
                    onClick={handleOpenInPlayground}
                  >
                    <Play className="mr-2 size-3.5" />
                    Open in playground
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </>
          )}

          {view === "raw" && (
            <>
              <Separator orientation="vertical" className="h-4" />

              <div className="relative">
                <TooltipWrapper content="Search">
                  <Button
                    variant="minimal"
                    size="icon-2xs"
                    onClick={() => setIsSearchExpanded(true)}
                    aria-label="Search"
                    className={cn(
                      "text-light-slate hover:text-foreground",
                      isSearchExpanded && "invisible",
                    )}
                  >
                    <Search />
                  </Button>
                </TooltipWrapper>
                {isSearchExpanded && (
                  <div className="absolute right-0 top-1/2 z-10 flex h-7 w-48 -translate-y-1/2 items-center rounded border border-border bg-background">
                    <Search className="ml-1.5 size-3.5 shrink-0 text-light-slate" />
                    <DebounceInput
                      ref={searchInputRef}
                      value={localSearch}
                      placeholder="Search..."
                      onValueChange={(v) => setLocalSearch(v as string)}
                      onKeyDown={(e) => e.key === "Escape" && closeSearch()}
                      className="comet-body-xs h-7 flex-1 border-0 bg-transparent px-1.5 focus-visible:ring-0"
                    />
                    <button
                      type="button"
                      onClick={closeSearch}
                      aria-label="Close search"
                      className="mr-1 flex size-4 shrink-0 items-center justify-center text-light-slate transition-colors hover:text-foreground"
                    >
                      <X className="size-3" />
                    </button>
                  </div>
                )}
              </div>
            </>
          )}

          <TooltipWrapper content="Copy prompt">
            <Button
              variant="minimal"
              size="icon-2xs"
              onClick={handleCopy}
              className="text-light-slate hover:text-foreground"
            >
              <Copy />
            </Button>
          </TooltipWrapper>
        </div>
      </div>

      <div className={cn("p-4", !isOpen && "hidden")}>{renderContent()}</div>

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
