import React, { ReactElement, useCallback, useMemo, useState } from "react";
import {
  ChevronLeft,
  ChevronRight,
  Clock,
  FileText,
  GitCommitVertical,
  MessagesSquare,
} from "lucide-react";
import toLower from "lodash/toLower";

import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/ui/hover-card";
import { Separator } from "@/ui/separator";
import { Spinner } from "@/ui/spinner";
import SearchInput from "@/shared/SearchInput/SearchInput";
import NoOptions from "@/v2/components/LoadableSelectBox/NoOptions";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import StageTag from "@/v2/pages-shared/version-history/StageTag";
import EnvironmentBadgeList from "@/shared/EnvironmentLabel/EnvironmentBadgeList";
import useProjectPromptsList from "@/api/prompts/useProjectPromptsList";
import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";
import { Prompt, PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { pickHighestStage } from "@/utils/version-stages";
import { cn } from "@/lib/utils";
import { formatDate, getTimeFromNow } from "@/lib/date";

const DEFAULT_LOADED_PROMPTS = 1000;

export type PromptLibrarySelection = {
  promptId: string;
  versionId?: string;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
};

const TEMPLATE_STRUCTURE_OPTIONS = [
  {
    value: PROMPT_TEMPLATE_STRUCTURE.TEXT,
    label: "Text prompt",
    listLabel: "Text prompts",
    description: "Loads into a single message, e.g. system prompt.",
    icon: FileText,
    iconClassName: "text-[var(--color-turquoise)]",
  },
  {
    value: PROMPT_TEMPLATE_STRUCTURE.CHAT,
    label: "Chat prompt",
    listLabel: "Chat prompts",
    description:
      "Loads as multiple messages across system, user, and assistant roles.",
    icon: MessagesSquare,
    iconClassName: "text-[var(--color-burgundy)]",
  },
];

type PromptLibraryMenuProps = {
  projectId: string;
  filterByTemplateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  onSelect: (selection: PromptLibrarySelection) => void;
  onOpenChange?: (open: boolean) => void;
  trigger: ReactElement;
  /**
   * When false, the version-submenu hovercard is hidden and rows always
   * select the latest version. Use this from callers that can't forward the
   * picked `versionId` through their state (e.g. compact PromptsSelectBox),
   * to avoid silently dropping the user's version pick.
   */
  enableVersionSelect?: boolean;
  /**
   * Ask for the prompt type first when the library holds both text and chat
   * prompts. With only one type there, its list opens directly.
   */
  chooseTemplateStructure?: boolean;
};

const PromptLibraryMenu: React.FC<PromptLibraryMenuProps> = ({
  projectId,
  filterByTemplateStructure,
  onSelect,
  onOpenChange,
  trigger,
  enableVersionSelect = true,
  chooseTemplateStructure = false,
}) => {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [chosenStructure, setChosenStructure] =
    useState<PROMPT_TEMPLATE_STRUCTURE>();

  const { data, isLoading } = useProjectPromptsList(
    {
      projectId,
      page: 1,
      size: DEFAULT_LOADED_PROMPTS,
    },
    { enabled: open, staleTime: 60_000 },
  );

  const structureFilter = filterByTemplateStructure ?? chosenStructure;

  const hasBothStructures = useMemo(() => {
    const allPrompts = data?.content ?? [];
    return TEMPLATE_STRUCTURE_OPTIONS.every(({ value }) =>
      allPrompts.some((p) => p.template_structure === value),
    );
  }, [data?.content]);

  const isChoosingStructure =
    chooseTemplateStructure &&
    !filterByTemplateStructure &&
    (isLoading || hasBothStructures);

  const filteredPrompts = useMemo(() => {
    const allPrompts = data?.content ?? [];
    const byStructure = structureFilter
      ? allPrompts.filter((p) => p.template_structure === structureFilter)
      : allPrompts;
    if (!search) return byStructure;
    const q = toLower(search);
    return byStructure.filter((p) => toLower(p.name).includes(q));
  }, [data?.content, structureFilter, search]);

  const handleOpenChange = useCallback(
    (next: boolean) => {
      setOpen(next);
      if (!next) {
        setSearch("");
        setChosenStructure(undefined);
      }
      onOpenChange?.(next);
    },
    [onOpenChange],
  );

  const handleBackToStructures = useCallback(() => {
    setChosenStructure(undefined);
    setSearch("");
  }, []);

  const handleSelect = useCallback(
    (selection: PromptLibrarySelection) => {
      onSelect(selection);
      handleOpenChange(false);
    },
    [onSelect, handleOpenChange],
  );

  return (
    <Popover open={open} onOpenChange={handleOpenChange}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent
        align="end"
        className="w-[320px] p-0"
        data-testid="prompt-library-menu"
        onCloseAutoFocus={(e) => e.preventDefault()}
        onInteractOutside={(e) => {
          const target = e.target as HTMLElement | null;
          if (target?.closest("[data-prompt-versions-submenu]")) {
            e.preventDefault();
          }
        }}
      >
        {isChoosingStructure && !chosenStructure ? (
          isLoading ? (
            <div className="flex items-center justify-center py-4">
              <Spinner />
            </div>
          ) : (
            <TemplateStructureOptions onSelect={setChosenStructure} />
          )
        ) : (
          <>
            {chosenStructure && (
              <>
                <button
                  type="button"
                  className="comet-body-s flex h-9 w-full items-center gap-1 px-2 text-left text-foreground"
                  onClick={handleBackToStructures}
                  data-testid="prompt-library-menu-back"
                >
                  <ChevronLeft className="size-3.5 shrink-0 text-light-slate" />
                  {
                    TEMPLATE_STRUCTURE_OPTIONS.find(
                      ({ value }) => value === chosenStructure,
                    )?.listLabel
                  }
                </button>
                <Separator />
              </>
            )}
            <div className="px-1 pt-1">
              <SearchInput
                searchText={search}
                setSearchText={setSearch}
                placeholder="Search"
                variant="ghost"
                dimension="sm"
              />
            </div>
            <Separator className="my-1" />
            <div className="max-h-[40vh] overflow-y-auto p-1">
              {isLoading ? (
                <div className="flex items-center justify-center py-4">
                  <Spinner />
                </div>
              ) : filteredPrompts.length === 0 ? (
                <NoOptions text={search ? "No search results" : "No prompts"} />
              ) : (
                filteredPrompts.map((prompt) => (
                  <PromptRow
                    key={prompt.id}
                    prompt={prompt}
                    onSelect={handleSelect}
                    enableVersionSelect={enableVersionSelect}
                  />
                ))
              )}
            </div>
          </>
        )}
      </PopoverContent>
    </Popover>
  );
};

type TemplateStructureOptionsProps = {
  onSelect: (value: PROMPT_TEMPLATE_STRUCTURE) => void;
};

const TemplateStructureOptions: React.FC<TemplateStructureOptionsProps> = ({
  onSelect,
}) => (
  <div className="p-1" data-testid="prompt-library-structure-options">
    {TEMPLATE_STRUCTURE_OPTIONS.map(
      ({ value, label, description, icon: Icon, iconClassName }, index) => (
        <React.Fragment key={value}>
          {index > 0 && <Separator className="my-1" />}
          <button
            type="button"
            className="flex w-full flex-col gap-0.5 rounded-md px-2 py-1.5 text-left hover:bg-primary-foreground"
            onClick={() => onSelect(value)}
            data-testid={`prompt-library-structure-${value}`}
          >
            <span className="flex items-center gap-1.5">
              <Icon className={cn("size-3.5 shrink-0", iconClassName)} />
              <span className="comet-body-s text-foreground">{label}</span>
            </span>
            <span className="comet-body-xs text-light-slate">
              {description}
            </span>
          </button>
        </React.Fragment>
      ),
    )}
  </div>
);

type PromptRowProps = {
  prompt: Prompt;
  onSelect: (selection: PromptLibrarySelection) => void;
  enableVersionSelect: boolean;
};

const PromptRow: React.FC<PromptRowProps> = ({
  prompt,
  onSelect,
  enableVersionSelect,
}) => {
  const [hoverOpen, setHoverOpen] = useState(false);

  const latestStage = pickHighestStage(prompt.latest_version?.tags);
  const latestEnvironments = prompt.latest_version?.environments;
  const latestLabel =
    prompt.version_count > 0 ? `v${prompt.version_count}` : "";

  const row = (
    <div
      role="button"
      tabIndex={0}
      className="comet-body-s flex h-9 cursor-pointer items-center gap-2 rounded-md px-2 hover:bg-primary-foreground"
      onClick={() =>
        onSelect({
          promptId: prompt.id,
          templateStructure: prompt.template_structure,
        })
      }
    >
      <span className="truncate">{prompt.name}</span>
      <div className="ml-auto flex shrink-0 items-center gap-1.5">
        {latestLabel && (
          <span className="comet-body-xs flex items-center gap-0.5 text-light-slate">
            <GitCommitVertical className="size-3.5 shrink-0" />
            {latestLabel}
          </span>
        )}
        {latestStage && <StageTag value={latestStage} size="xs" />}
        <EnvironmentBadgeList
          names={latestEnvironments}
          size="sm"
          withOverflow
          compact
          maxWidth={60}
        />
        {enableVersionSelect && (
          <ChevronRight className="size-3.5 shrink-0 text-light-slate" />
        )}
      </div>
    </div>
  );

  if (!enableVersionSelect) return row;

  return (
    <HoverCard
      openDelay={120}
      closeDelay={120}
      open={hoverOpen}
      onOpenChange={setHoverOpen}
    >
      <HoverCardTrigger asChild>{row}</HoverCardTrigger>
      <HoverCardContent
        side="left"
        align="start"
        sideOffset={8}
        className="w-[220px] p-1"
        data-prompt-versions-submenu=""
        data-testid="prompt-versions-submenu"
      >
        <PromptVersionsList
          promptId={prompt.id}
          activeVersionId={prompt.latest_version?.id}
          enabled={hoverOpen}
          onSelect={(versionId) =>
            onSelect({
              promptId: prompt.id,
              versionId,
              templateStructure: prompt.template_structure,
            })
          }
        />
      </HoverCardContent>
    </HoverCard>
  );
};

type PromptVersionsListProps = {
  promptId: string;
  activeVersionId?: string;
  enabled: boolean;
  onSelect: (versionId: string) => void;
};

const PromptVersionsList: React.FC<PromptVersionsListProps> = ({
  promptId,
  activeVersionId,
  enabled,
  onSelect,
}) => {
  const { data, isLoading } = usePromptVersionsById(
    {
      promptId,
      page: 1,
      size: 25,
      sorting: [{ id: "created_at", desc: true }],
    },
    {
      enabled,
      staleTime: 60_000,
    },
  );

  const versions = data?.content ?? [];

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-3">
        <Spinner />
      </div>
    );
  }

  if (versions.length === 0) {
    return (
      <div className="comet-body-xs p-2 text-light-slate">No versions</div>
    );
  }

  return (
    <div className="max-h-[40vh] overflow-y-auto">
      {versions.map((version) => {
        const label = version.version_number ?? version.commit;
        const isActive = version.id === activeVersionId;
        const stage = pickHighestStage(version.tags);
        return (
          <div
            key={version.id}
            role="button"
            tabIndex={0}
            className={cn(
              "comet-body-s flex h-8 cursor-pointer items-center gap-2 rounded-md px-3 hover:bg-primary-foreground",
              isActive && "bg-primary-100 text-primary",
            )}
            onClick={() => onSelect(version.id)}
          >
            <span className={cn("shrink-0", isActive && "text-primary")}>
              {label}
            </span>
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
              <span className="comet-body-xs ml-auto flex shrink-0 items-center gap-1 text-light-slate">
                <Clock className="size-3" />
                {getTimeFromNow(version.created_at)}
              </span>
            </TooltipWrapper>
          </div>
        );
      })}
    </div>
  );
};

export default PromptLibraryMenu;
