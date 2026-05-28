import React, { useCallback, useMemo, useState } from "react";
import {
  Blocks,
  ChevronDown,
  Clock,
  ExternalLink,
  FilePen,
  LucideIcon,
  Pencil,
  Play,
  Sparkles,
  Undo2,
  User,
} from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";
import { StringParam, useQueryParam } from "use-query-params";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import {
  PromptVersion,
  PromptWithLatestVersion,
  PROMPT_TEMPLATE_STRUCTURE,
} from "@/types/prompts";
import { Separator } from "@/ui/separator";
import {
  FormFieldCard,
  FormFieldModeSelect,
} from "@/v2/pages-shared/llm/FormFieldCard";
import CodeBlockCopy from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/CodeBlock/CodeBlockCopy";
import { Skeleton } from "@/ui/skeleton";
import CodeHighlighter, {
  SUPPORTED_LANGUAGE,
} from "@/shared/CodeHighlighter/CodeHighlighter";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import EditPromptSheet from "@/v2/pages/PromptPage/PromptTab/EditPromptSheet";
import VersionHistoryTimeline, {
  VersionHistoryItem,
} from "@/v2/pages-shared/version-history/VersionHistoryTimeline";
import DiffVersionMenu from "@/v2/pages-shared/version-history/DiffVersionMenu";
import StageTag from "@/v2/pages-shared/version-history/StageTag";
import ComparePromptVersionDialog from "@/v2/pages/PromptPage/CommitsTab/ComparePromptVersionDialog";
import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";
import usePromptVersionById, {
  useFetchPromptVersion,
} from "@/api/prompts/usePromptVersionById";
import EnvironmentBadgeList from "@/shared/EnvironmentLabel/EnvironmentBadgeList";
import ImproveInPlaygroundButton from "@/v2/pages/PromptPage/ImproveInPlaygroundButton";
import useLoadPromptIntoPlayground from "@/v2/pages-shared/playground/useLoadPromptIntoPlayground";
import { getTimeFromNow } from "@/lib/date";
import { pickHighestStage } from "@/utils/version-stages";
import DeployToEnvironmentMenu from "./DeployToEnvironmentMenu";
import RestoreVersionDialog from "./RestoreVersionDialog";
import ChatPromptView from "./ChatPromptView";
import TextPromptView from "./TextPromptView";
import { usePermissions } from "@/contexts/PermissionsContext";
import { buildDocsUrl, cn } from "@/lib/utils";

type ViewMode = "pretty" | "json";

const VIEW_MODE_OPTIONS: Array<{
  value: ViewMode;
  label: string;
  icon?: LucideIcon;
}> = [
  { value: "pretty", label: "Pretty", icon: Sparkles },
  { value: "json", label: "JSON" },
];

interface PromptTabInterface {
  prompt?: PromptWithLatestVersion;
}

interface VersionWithMaybeAuthor extends PromptVersion {
  created_by?: string;
}

const PromptTab = ({ prompt }: PromptTabInterface) => {
  const {
    permissions: { canUsePlayground, canConfigureWorkspaceSettings },
  } = usePermissions();

  const [openEditPrompt, setOpenEditPrompt] = useState(false);
  const [openCompare, setOpenCompare] = useState(false);
  const [openLoadConfirm, setOpenLoadConfirm] = useState(false);
  const [versionToRestore, setVersionToRestore] =
    useState<PromptVersion | null>(null);
  const [compareAgainstVersionId, setCompareAgainstVersionId] = useState<
    string | null
  >(null);
  const [viewMode, setViewMode] = useState<ViewMode>("pretty");

  const { loadPrompt, isPlaygroundEmpty, isPendingProviderKeys } =
    useLoadPromptIntoPlayground();

  const [activeVersionId, setActiveVersionId] = useQueryParam(
    "activeVersionId",
    StringParam,
  );

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
      environments: v.environments ?? [],
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

  const activeStage = pickHighestStage(activeVersion?.tags);
  const activeAuthor =
    (activeVersion as VersionWithMaybeAuthor | undefined)?.created_by ?? "";
  const activeVersionEnvironments = useMemo(
    () => activeVersion?.environments ?? [],
    [activeVersion?.environments],
  );

  const handleRestoreVersionClick = (version: PromptVersion) =>
    setVersionToRestore(version);

  const isChatPrompt =
    prompt?.template_structure === PROMPT_TEMPLATE_STRUCTURE.CHAT;
  const isLatest = effectiveVersionId === prompt?.latest_version?.id;
  const template = activeVersion?.template ?? "";
  const metadataJson = useMemo(
    () =>
      activeVersion?.metadata
        ? JSON.stringify(activeVersion.metadata, null, 2)
        : "",
    [activeVersion?.metadata],
  );

  const fetchPromptVersion = useFetchPromptVersion();

  const handleLoadIntoPlayground = useCallback(async () => {
    if (!prompt?.id) return;
    // activeVersion can hold stale data when switching versions because
    // usePromptVersionById uses placeholderData: keepPreviousData — fall back
    // to an imperative fetch so we always load the version actually selected.
    let version = activeVersion;
    if (effectiveVersionId && activeVersion?.id !== effectiveVersionId) {
      try {
        version = await fetchPromptVersion({ versionId: effectiveVersionId });
      } catch {
        // Refetch failed — bail rather than ship stale `activeVersion`
        // (placeholder from the previously-selected version) into the
        // playground, which would silently load wrong content.
        return;
      }
    }
    loadPrompt({ prompt, version });
  }, [
    loadPrompt,
    prompt,
    activeVersion,
    effectiveVersionId,
    fetchPromptVersion,
  ]);

  const handleOpenInPlaygroundClick = useCallback(() => {
    if (isPlaygroundEmpty) {
      handleLoadIntoPlayground();
    } else {
      setOpenLoadConfirm(true);
    }
  }, [isPlaygroundEmpty, handleLoadIntoPlayground]);

  const handleSelectDiffVersion = useCallback((item: VersionHistoryItem) => {
    setCompareAgainstVersionId(item.id);
    setOpenCompare(true);
  }, []);

  // Gate skeleton on in-flight fetches only — once they settle, render even
  // if `activeVersion` is undefined (e.g. version fetch 404'd) so the page
  // doesn't get stuck on the skeleton; downstream sections handle the missing
  // version by falling back to `prompt.latest_version` or rendering empty.
  const isInitialLoading =
    !prompt ||
    isVersionsLoading ||
    (!!effectiveVersionId && isActiveVersionLoading);

  if (isInitialLoading) {
    return (
      <div className="grid grid-cols-1 gap-4 px-6 pt-2 xl:grid-cols-[7fr_3fr] xl:gap-6">
        <div className="min-w-0">
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
        <div className="hidden min-w-0 xl:block">
          <p className="comet-body-s-accented mb-1 ml-3">Version history</p>
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
    <div className="grid grid-cols-1 gap-4 px-6 pt-2 xl:grid-cols-[7fr_3fr] xl:gap-6">
      <div className="flex min-w-0 flex-col gap-3">
        <div className="xl:hidden">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="outline"
                size="sm"
                className="w-full justify-between font-normal"
              >
                <span className="flex min-w-0 items-center gap-2">
                  <span className="comet-body-s-accented shrink-0 text-foreground">
                    {activeVersionLabel || "v—"}
                  </span>
                  <EnvironmentBadgeList
                    names={activeVersionEnvironments}
                    size="sm"
                    withOverflow
                    maxWidth={200}
                  />
                  <span className="comet-body-xs shrink-0 text-muted-slate">
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
                    <EnvironmentBadgeList
                      names={item.environments}
                      size="sm"
                      withOverflow
                      compact
                      maxWidth={120}
                    />
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
          <div className="flex items-center gap-2 px-4 pb-1.5 pt-3">
            <div className="flex min-w-0 flex-1 items-center gap-2">
              <span className="comet-body-accented shrink-0 text-foreground">
                {activeVersionLabel || "v—"}
              </span>
              {activeStage && <StageTag value={activeStage} size="sm" />}
              <EnvironmentBadgeList
                names={activeVersionEnvironments}
                size="sm"
                withOverflow
                maxWidth={320}
              />
              {historyItems.length > 1 && (
                <>
                  <Separator orientation="vertical" className="mx-1 h-4 shrink-0" />
                  <DiffVersionMenu
                    currentItemId={effectiveVersionId}
                    versions={historyItems}
                    onSelectVersion={handleSelectDiffVersion}
                    triggerLabel="Diff"
                  />
                </>
              )}
            </div>

            <div className="flex shrink-0 flex-wrap items-center gap-1 md:ml-auto">
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="ghost" size="sm" className="px-0">
                    <Play className="mr-1.5 size-3.5" />
                    Use
                    <ChevronDown className="ml-1 size-3" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  {canUsePlayground && (
                    <DropdownMenuItem
                      disabled={!prompt || isPendingProviderKeys}
                      onClick={handleOpenInPlaygroundClick}
                      className="px-3"
                    >
                      <Blocks className="mr-2 size-3.5 shrink-0 text-light-slate" />
                      Load in Prompt playground
                    </DropdownMenuItem>
                  )}
                  <DropdownMenuSeparator />
                  <DropdownMenuItem asChild className="px-3">
                    <a
                      href={buildDocsUrl(
                        "/development/prompt-library/getting-started",
                      )}
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      Reference prompt in code
                      <ExternalLink className="ml-2 size-3.5 shrink-0" />
                    </a>
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>

              {canUsePlayground && !isChatPrompt && (
                <>
                  <Separator orientation="vertical" className="mx-1 h-4" />
                  <ImproveInPlaygroundButton
                    prompt={prompt}
                    activeVersion={activeVersion}
                  />
                </>
              )}

              {canConfigureWorkspaceSettings && (
                <>
                  <Separator orientation="vertical" className="mx-1 h-4" />
                  <DeployToEnvironmentMenu
                    promptId={prompt.id}
                    versionId={effectiveVersionId}
                    versionLabel={activeVersionLabel}
                    versions={versions}
                    totalVersions={total}
                    activeEnvironments={activeVersionEnvironments}
                  />
                </>
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
                onClick={() => setOpenEditPrompt(true)}
              >
                <Pencil className="mr-1.5 size-3.5" />
                Edit
              </Button>
            </div>
          </div>

          {/* Change description + meta */}
          <div className="space-y-1 px-4 pb-3">
            {activeVersion?.change_description && (
              <div className="comet-body-s flex min-w-0 items-center gap-1.5 text-muted-slate">
                <FilePen className="size-3.5 shrink-0 text-muted-slate" />
                <TooltipWrapper content={activeVersion.change_description}>
                  <span className="w-fit max-w-full truncate">
                    {activeVersion.change_description}
                  </span>
                </TooltipWrapper>
              </div>
            )}
            <div className="comet-body-s flex flex-wrap items-center gap-x-3 gap-y-1 text-muted-slate">
              {activeVersion?.created_at && (
                <span className="flex items-center gap-1.5">
                  <Clock className="size-3.5" />
                  {getTimeFromNow(activeVersion.created_at)}
                </span>
              )}
              {activeAuthor && (
                <span className="flex items-center gap-1.5">
                  <User className="size-3.5" />
                  {activeAuthor}
                </span>
              )}
            </div>
          </div>

          {/* Prompt section */}
          <div className="px-4 pb-4">
            <FormFieldCard
              title="Prompt"
              actions={
                <>
                  <FormFieldModeSelect
                    value={viewMode}
                    options={VIEW_MODE_OPTIONS}
                    onChange={setViewMode}
                  />
                  <Separator orientation="vertical" className="-ml-2 h-3" />
                  <CodeBlockCopy text={template} />
                </>
              }
              bodyClassName={cn(
                viewMode === "json" && isChatPrompt && "px-0 pt-2",
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
                  hideCopy
                  transparent
                />
              ) : (
                <pre className="comet-code whitespace-pre-wrap break-words text-foreground">
                  {template}
                </pre>
              )}
            </FormFieldCard>
          </div>

          {/* Metadata section */}
          {metadataJson && (
            <div className="px-4 pb-4">
              <FormFieldCard
                title="Metadata"
                actions={<CodeBlockCopy text={metadataJson} />}
                bodyClassName="px-0 pt-2"
              >
                <CodeHighlighter
                  data={metadataJson}
                  language={SUPPORTED_LANGUAGE.json}
                  hideCopy
                  transparent
                />
              </FormFieldCard>
            </div>
          )}
        </div>
      </div>

      {/* Right sidebar (visible only on xl+ screens) */}
      <div className="hidden min-w-0 xl:block">
        <p className="comet-body-s-accented mb-1 ml-3">Version history</p>
        <VersionHistoryTimeline
          items={historyItems}
          selectedId={effectiveVersionId}
          onSelect={(item) => setActiveVersionId(item.id)}
        />
      </div>

      <EditPromptSheet
        open={openEditPrompt}
        setOpen={setOpenEditPrompt}
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
        initialBaseVersionId={compareAgainstVersionId ?? undefined}
        initialDiffVersionId={effectiveVersionId || undefined}
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
