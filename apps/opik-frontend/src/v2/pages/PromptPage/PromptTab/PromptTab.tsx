import React, { useCallback, useMemo, useRef, useState } from "react";
import {
  ChevronDown,
  Clock,
  Copy,
  FilePen,
  Pencil,
  Play,
  Sparkles,
  Undo2,
  User,
  Wand2,
} from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";
import { StringParam, useQueryParam } from "use-query-params";
import copy from "clipboard-copy";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import {
  PromptVersion,
  PromptWithLatestVersion,
  PROMPT_TEMPLATE_STRUCTURE,
} from "@/types/prompts";
import { Label } from "@/ui/label";
import { Separator } from "@/ui/separator";
import { Skeleton } from "@/ui/skeleton";
import CodeHighlighter, {
  SUPPORTED_LANGUAGE,
} from "@/shared/CodeHighlighter/CodeHighlighter";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import UseThisPromptDialog from "@/v2/pages/PromptPage/PromptTab/UseThisPromptDialog";
import EditPromptSheet from "@/v2/pages/PromptPage/PromptTab/EditPromptSheet";
import VersionHistoryTimeline, {
  VersionHistoryItem,
} from "@/v2/pages-shared/version-history/VersionHistoryTimeline";
import DiffVersionMenu from "@/v2/pages-shared/version-history/DiffVersionMenu";
import StageTag from "@/v2/pages-shared/version-history/StageTag";
import ComparePromptVersionDialog from "@/v2/pages/PromptPage/CommitsTab/ComparePromptVersionDialog";
import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";
import usePromptVersionById from "@/api/prompts/usePromptVersionById";
import EnvironmentBadge from "@/shared/EnvironmentLabel/EnvironmentBadge";
import ImproveInPlaygroundButton from "@/v2/pages/PromptPage/ImproveInPlaygroundButton";
import useLoadPlayground from "@/v2/pages-shared/playground/useLoadPlayground";
import { parsePromptVersionContent } from "@/lib/llm";
import { getTimeFromNow } from "@/lib/date";
import { VersionStage, isProdTag } from "@/utils/version-stages";
import DeployToEnvironmentMenu from "./DeployToEnvironmentMenu";
import PromptContentBlock from "./PromptContentBlock";
import RestoreVersionDialog from "./RestoreVersionDialog";
import ChatPromptView from "./ChatPromptView";
import TextPromptView from "./TextPromptView";
import { useToast } from "@/ui/use-toast";
import { usePermissions } from "@/contexts/PermissionsContext";
import { cn } from "@/lib/utils";

type ViewMode = "pretty" | "raw";

interface PromptTabInterface {
  prompt?: PromptWithLatestVersion;
}

interface VersionWithMaybeAuthor extends PromptVersion {
  created_by?: string;
}

const versionHasProdTag = (version: PromptVersion | undefined) =>
  Boolean(version?.tags?.some(isProdTag));

const PromptTab = ({ prompt }: PromptTabInterface) => {
  const {
    permissions: { canUsePlayground, canConfigureWorkspaceSettings },
  } = usePermissions();
  const { toast } = useToast();

  const [openUseThisPrompt, setOpenUseThisPrompt] = useState(false);
  const [openEditPrompt, setOpenEditPrompt] = useState(false);
  const [openCompare, setOpenCompare] = useState(false);
  const [openLoadConfirm, setOpenLoadConfirm] = useState(false);
  const [versionToRestore, setVersionToRestore] =
    useState<PromptVersion | null>(null);
  const [viewMode, setViewMode] = useState<ViewMode>("pretty");

  const { loadPlayground, isPlaygroundEmpty, isPendingProviderKeys } =
    useLoadPlayground();

  const [activeVersionId, setActiveVersionId] = useQueryParam(
    "activeVersionId",
    StringParam,
  );

  const editPromptResetKeyRef = useRef(0);

  const { data, isLoading: isVersionsLoading } = usePromptVersionsById(
    {
      promptId: prompt?.id || "",
      page: 1,
      size: 25,
      sorting: [{ id: "created_at", desc: true }],
    },
    {
      enabled: !!prompt?.id,
      refetchInterval: 30000,
    },
  );

  const versions = data?.content as VersionWithMaybeAuthor[] | undefined;
  const total = data?.total ?? versions?.length ?? 0;

  const historyItems = useMemo<VersionHistoryItem[]>(() => {
    if (!versions) return [];
    return versions.map((v, idx) => ({
      id: v.id,
      label: `v${total - idx}`,
      tags: v.tags ?? [],
      description: v.change_description,
      created_at: v.created_at,
      created_by: v.created_by,
      environment: v.environment ?? null,
    }));
  }, [versions, total]);

  const effectiveVersionId = useMemo(() => {
    if (activeVersionId && versions?.some((v) => v.id === activeVersionId)) {
      return activeVersionId;
    }
    return prompt?.latest_version?.id ?? versions?.[0]?.id ?? "";
  }, [activeVersionId, versions, prompt?.latest_version?.id]);

  const { data: activeVersion, isLoading: isActiveVersionLoading } =
    usePromptVersionById(
      { versionId: effectiveVersionId },
      {
        enabled: !!effectiveVersionId,
        placeholderData: keepPreviousData,
      },
    );

  const activeIndex = useMemo(
    () => versions?.findIndex((v) => v.id === effectiveVersionId) ?? -1,
    [versions, effectiveVersionId],
  );
  const activeVersionLabel =
    activeIndex >= 0 && total > 0 ? `v${total - activeIndex}` : "";

  const activeIsProd = versionHasProdTag(activeVersion);
  const activeAuthor =
    (activeVersion as VersionWithMaybeAuthor | undefined)?.created_by ?? "";
  const activeVersionEnvironment = activeVersion?.environment ?? null;

  const handleOpenEditPrompt = (value: boolean) => {
    editPromptResetKeyRef.current += 1;
    setOpenEditPrompt(value);
  };

  const handleRestoreVersionClick = (version: PromptVersion) =>
    setVersionToRestore(version);

  const isChatPrompt =
    prompt?.template_structure === PROMPT_TEMPLATE_STRUCTURE.CHAT;
  const isLatest = effectiveVersionId === prompt?.latest_version?.id;
  const template = activeVersion?.template ?? "";
  const metadataJson = activeVersion?.metadata
    ? JSON.stringify(activeVersion.metadata, null, 2)
    : "";

  const handleCopyPrompt = useCallback(async () => {
    if (!template) return;
    await copy(template);
    toast({ description: "Prompt copied to clipboard" });
  }, [template, toast]);

  const handleCopyMetadata = useCallback(async () => {
    if (!metadataJson) return;
    await copy(metadataJson);
    toast({ description: "Metadata copied to clipboard" });
  }, [metadataJson, toast]);

  const handleLoadIntoPlayground = useCallback(() => {
    loadPlayground({
      promptContent: parsePromptVersionContent(
        activeVersion ?? prompt?.latest_version,
      ),
      promptId: prompt?.id,
      promptVersionId: activeVersion?.id ?? prompt?.latest_version?.id,
      templateStructure: prompt?.template_structure,
    });
  }, [loadPlayground, prompt, activeVersion]);

  const handleOpenInPlaygroundClick = useCallback(() => {
    if (isPlaygroundEmpty) {
      handleLoadIntoPlayground();
    } else {
      setOpenLoadConfirm(true);
    }
  }, [isPlaygroundEmpty, handleLoadIntoPlayground]);

  const handleSelectDiffVersion = useCallback(() => {
    // The dropdown picks the comparison target; ComparePromptVersionDialog
    // re-renders its own version pickers so we just open the dialog here.
    setOpenCompare(true);
  }, []);

  const isInitialLoading =
    !prompt ||
    isVersionsLoading ||
    (!!effectiveVersionId && (isActiveVersionLoading || !activeVersion));

  if (isInitialLoading) {
    return (
      <div className="flex flex-col gap-4 px-6 pt-2 xl:flex-row xl:gap-6">
        <div className="min-w-0 flex-1">
          <div className="rounded-md border bg-background">
            <div className="flex items-center justify-between border-b p-4 py-3">
              <Skeleton className="h-5 w-16" />
              <Skeleton className="h-5 w-48" />
            </div>
            <div className="border-b p-4">
              <Skeleton className="h-3 w-2/5" />
            </div>
            <div className="border-b p-4">
              <Skeleton className="h-[140px] w-full" />
            </div>
            <div className="p-4">
              <Skeleton className="h-[200px] w-full" />
            </div>
          </div>
        </div>
        <div className="hidden w-[340px] shrink-0 xl:block">
          <p className="comet-body-s-accented ml-3 mt-1">Version history</p>
          <div className="space-y-3 p-4">
            {[0, 1, 2, 3, 4].map((i) => (
              <Skeleton key={i} className="h-16 w-full" />
            ))}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4 px-6 pt-2 xl:flex-row xl:gap-6">
      <div className="flex min-w-0 flex-1 flex-col gap-3">
        <div className="xl:hidden">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="outline"
                size="sm"
                className="w-full justify-between font-normal"
              >
                <span className="flex min-w-0 items-center gap-2 truncate">
                  <span className="comet-body-s-accented text-foreground">
                    {activeVersionLabel || "v—"}
                  </span>
                  <EnvironmentBadge name={activeVersionEnvironment} size="sm" />
                  <span className="comet-body-xs text-muted-slate">
                    {activeVersion?.created_at &&
                      getTimeFromNow(activeVersion.created_at)}
                  </span>
                </span>
                <ChevronDown className="size-4 shrink-0 text-light-slate" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent
              align="start"
              className="max-h-[60vh] w-[var(--radix-dropdown-menu-trigger-width)] overflow-y-auto"
            >
              {historyItems.map((item) => (
                <DropdownMenuItem
                  key={item.id}
                  onSelect={() => setActiveVersionId(item.id)}
                  className={cn(
                    "flex flex-col items-start gap-1",
                    item.id === effectiveVersionId &&
                      "bg-muted text-foreground focus:bg-muted",
                  )}
                >
                  <div className="flex w-full min-w-0 items-center gap-2">
                    <span className="comet-body-s-accented shrink-0">
                      {item.label}
                    </span>
                    <EnvironmentBadge name={item.environment} size="sm" />
                  </div>
                  <span className="comet-body-xs text-muted-slate">
                    {getTimeFromNow(item.created_at)}
                    {item.created_by ? ` · ${item.created_by}` : ""}
                  </span>
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
        <div className="rounded-md border bg-background">
          {/* Toolbar */}
          <div className="flex flex-wrap items-center gap-2 p-4 py-3">
            <div className="flex items-center gap-2">
              <span className="comet-body-accented text-foreground">
                {activeVersionLabel || "v—"}
              </span>
              {activeIsProd && <StageTag value={VersionStage.PROD} size="sm" />}
              <EnvironmentBadge name={activeVersionEnvironment} size="sm" />
              <Separator orientation="vertical" className="mx-1 h-4" />
              <DiffVersionMenu
                currentItemId={effectiveVersionId}
                versions={historyItems}
                onSelectVersion={handleSelectDiffVersion}
              />
            </div>

            <div className="flex flex-wrap items-center gap-1 md:ml-auto">
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="sm" className="px-0">
                    <Play className="mr-1.5 size-3.5" />
                    Use prompt
                    <ChevronDown className="ml-1 size-3.5" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  <DropdownMenuItem onClick={() => setOpenUseThisPrompt(true)}>
                    Show code snippets
                  </DropdownMenuItem>
                  {canUsePlayground && (
                    <DropdownMenuItem
                      disabled={!prompt || isPendingProviderKeys}
                      onClick={handleOpenInPlaygroundClick}
                    >
                      Open in playground
                    </DropdownMenuItem>
                  )}
                </DropdownMenuContent>
              </DropdownMenu>

              {canConfigureWorkspaceSettings && (
                <>
                  <Separator orientation="vertical" className="mx-1 h-4" />
                  <DeployToEnvironmentMenu
                    promptId={prompt.id}
                    versionId={effectiveVersionId}
                    versionLabel={activeVersionLabel}
                    versions={versions}
                    totalVersions={total}
                    activeEnvironment={activeVersionEnvironment}
                  />
                </>
              )}

              <Separator orientation="vertical" className="mx-1 h-4" />

              {canUsePlayground && !isChatPrompt ? (
                <ImproveInPlaygroundButton
                  prompt={prompt}
                  activeVersion={activeVersion}
                />
              ) : (
                canUsePlayground && (
                  <TooltipWrapper content="Improve flow is available for text prompts">
                    <span>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="px-0"
                        disabled
                      >
                        <Wand2 className="mr-1.5 size-3.5" />
                        Improve prompt
                      </Button>
                    </span>
                  </TooltipWrapper>
                )
              )}

              <Separator orientation="vertical" className="mx-1 h-4" />

              {activeVersion && !isLatest && (
                <Button
                  variant="ghost"
                  size="sm"
                  className="px-0"
                  onClick={() => handleRestoreVersionClick(activeVersion)}
                >
                  <Undo2 className="mr-1.5 size-3.5" />
                  Restore
                </Button>
              )}

              <Button
                variant="ghost"
                size="sm"
                className={cn("px-0", activeVersion && !isLatest && "ml-3")}
                onClick={() => handleOpenEditPrompt(true)}
              >
                <Pencil className="mr-1.5 size-3.5" />
                Edit
              </Button>
            </div>
          </div>

          {/* Change description + meta */}
          <div className="px-4 pb-4">
            {activeVersion?.change_description && (
              <div className="comet-body-s flex items-center gap-2 text-muted-slate">
                <FilePen className="size-3.5 shrink-0 text-muted-slate" />
                <span>{activeVersion.change_description}</span>
              </div>
            )}
            <div className="comet-body-s mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-muted-slate">
              {activeVersion?.created_at && (
                <span className="flex items-center gap-1">
                  <Clock className="size-3" />
                  {getTimeFromNow(activeVersion.created_at)}
                </span>
              )}
              {activeAuthor && (
                <span className="flex items-center gap-1">
                  <User className="size-3" />
                  {activeAuthor}
                </span>
              )}
            </div>
          </div>

          {/* Prompt section */}
          <div className="space-y-1.5 px-4 pb-4">
            <Label>Prompt</Label>
            <PromptContentBlock
              toolbar={
                <>
                  <Button
                    variant="ghost"
                    size="2xs"
                    onClick={() =>
                      setViewMode((m) => (m === "pretty" ? "raw" : "pretty"))
                    }
                  >
                    {viewMode === "pretty" ? (
                      <>
                        Pretty <Sparkles className="ml-1 size-3" />
                      </>
                    ) : (
                      <>Raw</>
                    )}
                    <ChevronDown className="ml-1 size-3" />
                  </Button>
                  <TooltipWrapper content="Copy prompt">
                    <Button
                      variant="minimal"
                      size="icon-2xs"
                      onClick={handleCopyPrompt}
                    >
                      <Copy />
                    </Button>
                  </TooltipWrapper>
                </>
              }
              bodyClassName={cn(
                viewMode === "raw" && isChatPrompt && "px-0 pb-0",
              )}
            >
              {viewMode === "pretty" ? (
                isChatPrompt ? (
                  <ChatPromptView template={template} />
                ) : (
                  <TextPromptView template={template} />
                )
              ) : isChatPrompt ? (
                <CodeHighlighter
                  data={template}
                  language={SUPPORTED_LANGUAGE.json}
                />
              ) : (
                <pre className="comet-code whitespace-pre-wrap break-words text-foreground">
                  {template}
                </pre>
              )}
            </PromptContentBlock>
          </div>

          {/* Metadata section */}
          {metadataJson && (
            <div className="space-y-1.5 px-4 pb-4">
              <Label>Metadata</Label>
              <PromptContentBlock
                toolbar={
                  <>
                    <span className="comet-body-xs ml-3 uppercase tracking-wide text-foreground">
                      JSON
                    </span>
                    <TooltipWrapper content="Copy metadata">
                      <Button
                        variant="minimal"
                        size="icon-2xs"
                        onClick={handleCopyMetadata}
                      >
                        <Copy />
                      </Button>
                    </TooltipWrapper>
                  </>
                }
                bodyClassName="px-0 pb-0"
              >
                <CodeHighlighter
                  data={metadataJson}
                  language={SUPPORTED_LANGUAGE.json}
                  hideCopy
                />
              </PromptContentBlock>
            </div>
          )}
        </div>
      </div>

      {/* Right sidebar (visible only on xl+ screens) */}
      <div className="hidden w-[340px] shrink-0 xl:block">
        <p className="comet-body-s-accented ml-3 mt-1">Version history</p>
        <VersionHistoryTimeline
          items={historyItems}
          selectedId={effectiveVersionId}
          onSelect={(item) => setActiveVersionId(item.id)}
        />
      </div>

      <UseThisPromptDialog
        open={openUseThisPrompt}
        setOpen={setOpenUseThisPrompt}
        promptName={prompt.name}
        templateStructure={prompt.template_structure}
      />

      <EditPromptSheet
        key={editPromptResetKeyRef.current}
        open={openEditPrompt}
        setOpen={handleOpenEditPrompt}
        promptName={prompt.name}
        template={activeVersion?.template || ""}
        metadata={activeVersion?.metadata}
        templateStructure={prompt.template_structure}
        type={activeVersion?.type}
        onSetActiveVersionId={setActiveVersionId}
      />

      <RestoreVersionDialog
        open={!!versionToRestore}
        setOpen={(v) => setVersionToRestore(v ? versionToRestore : null)}
        versionToRestore={versionToRestore}
        versionLabel={
          versionToRestore
            ? historyItems.find((h) => h.id === versionToRestore.id)?.label
            : undefined
        }
        onSetActiveVersionId={setActiveVersionId}
      />

      <ComparePromptVersionDialog
        open={openCompare}
        setOpen={setOpenCompare}
        versions={versions ?? []}
      />

      <ConfirmDialog
        open={openLoadConfirm}
        setOpen={setOpenLoadConfirm}
        onConfirm={handleLoadIntoPlayground}
        title="Load prompt"
        description="Loading this prompt into the Playground will replace any unsaved changes. This action cannot be undone."
        confirmText="Load prompt"
      />
    </div>
  );
};

export default PromptTab;
