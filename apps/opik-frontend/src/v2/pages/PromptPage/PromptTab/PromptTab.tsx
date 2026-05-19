import React, { useCallback, useMemo, useRef, useState } from "react";
import {
  ChevronDown,
  Clock,
  Copy,
  FileText,
  Pencil,
  RotateCcw,
  Sparkles,
  Send,
  User,
  Wand2,
} from "lucide-react";
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
import { Skeleton } from "@/ui/skeleton";
import CodeHighlighter, {
  SUPPORTED_LANGUAGE,
} from "@/shared/CodeHighlighter/CodeHighlighter";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import UseThisPromptDialog from "@/v2/pages/PromptPage/PromptTab/UseThisPromptDialog";
import EditPromptVersionDialog from "@/v2/pages/PromptPage/PromptTab/EditPromptVersionDialog";
import VersionHistoryTimeline, {
  VersionHistoryItem,
} from "@/v2/pages-shared/version-history/VersionHistoryTimeline";
import DiffVersionMenu from "@/v2/pages-shared/version-history/DiffVersionMenu";
import StageTag from "@/v2/pages-shared/version-history/StageTag";
import ComparePromptVersionDialog from "@/v2/pages/PromptPage/CommitsTab/ComparePromptVersionDialog";
import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";
import usePromptVersionById from "@/api/prompts/usePromptVersionById";
import ImproveInPlaygroundButton from "@/v2/pages/PromptPage/ImproveInPlaygroundButton";
import useLoadPlayground from "@/v2/pages-shared/playground/useLoadPlayground";
import { parsePromptVersionContent } from "@/lib/llm";
import { getTimeFromNow } from "@/lib/date";
import {
  AgentConfigurationBasicStage,
  isProdTag,
} from "@/utils/agent-configurations";
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
    permissions: { canUsePlayground },
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
      { enabled: !!effectiveVersionId },
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
      <div className="flex gap-6 px-6 pt-2">
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
        <div className="w-[340px] shrink-0">
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
    <div className="flex gap-6 px-6 pt-2">
      <div className="min-w-0 flex-1">
        <div className="rounded-md border bg-background">
          {/* Toolbar */}
          <div className="flex flex-wrap items-center gap-2 border-b p-4 py-3">
            <div className="flex shrink-0 items-center gap-2">
              <span className="comet-body-s-accented rounded-md bg-muted px-2 py-0.5 text-foreground">
                {activeVersionLabel || "v—"}
              </span>
              {activeIsProd && (
                <StageTag value={AgentConfigurationBasicStage.PROD} size="sm" />
              )}
            </div>

            <div className="ml-auto flex flex-wrap items-center gap-1">
              <DiffVersionMenu
                currentItemId={effectiveVersionId}
                versions={historyItems}
                onSelectVersion={handleSelectDiffVersion}
              />

              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="sm">
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

              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="sm">
                    <Send className="mr-1.5 size-3.5" />
                    Deploy to
                    <ChevronDown className="ml-1 size-3.5" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  <DropdownMenuItem disabled>Coming soon</DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>

              {canUsePlayground && !isChatPrompt ? (
                <ImproveInPlaygroundButton
                  prompt={prompt}
                  activeVersion={activeVersion}
                />
              ) : (
                canUsePlayground && (
                  <TooltipWrapper content="Improve flow is available for text prompts">
                    <span>
                      <Button variant="ghost" size="sm" disabled>
                        <Wand2 className="mr-1.5 size-3.5" />
                        Improve prompt
                      </Button>
                    </span>
                  </TooltipWrapper>
                )
              )}

              <Button
                variant="ghost"
                size="sm"
                disabled={isLatest || !activeVersion}
                onClick={() =>
                  activeVersion && handleRestoreVersionClick(activeVersion)
                }
              >
                <RotateCcw className="mr-1.5 size-3.5" />
                Restore
              </Button>

              <Button
                variant="ghost"
                size="sm"
                onClick={() => handleOpenEditPrompt(true)}
              >
                <Pencil className="mr-1.5 size-3.5" />
                Edit
              </Button>
            </div>
          </div>

          {/* Change description + meta */}
          <div className="border-b p-4">
            {activeVersion?.change_description ? (
              <div className="comet-body-s flex items-start gap-2 text-foreground">
                <FileText className="mt-0.5 size-3.5 shrink-0 text-light-slate" />
                <span>{activeVersion.change_description}</span>
              </div>
            ) : (
              <div className="comet-body-s text-light-slate">
                No change description
              </div>
            )}
            <div className="comet-body-xs mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-light-slate">
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
          <div className="border-b p-4">
            <p className="comet-body-s-accented mb-2 text-foreground">Prompt</p>
            <div className="rounded-md border bg-soft-background">
              <div className="flex items-center justify-between border-b px-3 py-1.5">
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
              </div>
              <div className={cn("p-3", viewMode === "raw" && "p-0")}>
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
                  <pre className="comet-code whitespace-pre-wrap break-words p-3 text-foreground">
                    {template}
                  </pre>
                )}
              </div>
            </div>
          </div>

          {/* Metadata section */}
          {metadataJson && (
            <div className="p-4">
              <p className="comet-body-s-accented mb-2 text-foreground">
                Metadata
              </p>
              <div className="rounded-md border bg-soft-background">
                <div className="flex items-center justify-between border-b px-3 py-1.5">
                  <span className="comet-body-xs-accented uppercase tracking-wide text-light-slate">
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
                </div>
                <CodeHighlighter
                  data={metadataJson}
                  language={SUPPORTED_LANGUAGE.json}
                />
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Right sidebar */}
      <div className="w-[340px] shrink-0">
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

      <EditPromptVersionDialog
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
