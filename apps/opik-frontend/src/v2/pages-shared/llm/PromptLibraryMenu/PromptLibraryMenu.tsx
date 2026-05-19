import React, {
  ReactElement,
  useCallback,
  useMemo,
  useState,
} from "react";
import { ChevronRight, Clock, FileTerminal } from "lucide-react";
import toLower from "lodash/toLower";

import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/ui/hover-card";
import { Separator } from "@/ui/separator";
import { Spinner } from "@/ui/spinner";
import SearchInput from "@/shared/SearchInput/SearchInput";
import NoOptions from "@/shared/LoadableSelectBox/NoOptions";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import StageTag from "@/v2/pages-shared/version-history/StageTag";
import useProjectPromptsList from "@/api/prompts/useProjectPromptsList";
import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";
import { Prompt, PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { pickHighestStage } from "@/utils/agent-configurations";
import { cn } from "@/lib/utils";
import { formatDate, getTimeFromNow } from "@/lib/date";

const DEFAULT_LOADED_PROMPTS = 1000;

export type PromptLibrarySelection = {
  promptId: string;
  versionId?: string;
};

type PromptLibraryMenuProps = {
  projectId: string;
  filterByTemplateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  onSelect: (selection: PromptLibrarySelection) => void;
  trigger: ReactElement;
};

const PromptLibraryMenu: React.FC<PromptLibraryMenuProps> = ({
  projectId,
  filterByTemplateStructure,
  onSelect,
  trigger,
}) => {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");

  const { data, isLoading } = useProjectPromptsList(
    {
      projectId,
      page: 1,
      size: DEFAULT_LOADED_PROMPTS,
    },
    { enabled: open, staleTime: 60_000 },
  );

  const filteredPrompts = useMemo(() => {
    const allPrompts = data?.content ?? [];
    const byStructure = filterByTemplateStructure
      ? allPrompts.filter(
          (p) => p.template_structure === filterByTemplateStructure,
        )
      : allPrompts;
    if (!search) return byStructure;
    const q = toLower(search);
    return byStructure.filter((p) => toLower(p.name).includes(q));
  }, [data?.content, filterByTemplateStructure, search]);

  const handleOpenChange = useCallback((next: boolean) => {
    setOpen(next);
    if (!next) setSearch("");
  }, []);

  const handleSelect = useCallback(
    (selection: PromptLibrarySelection) => {
      onSelect(selection);
      setOpen(false);
    },
    [onSelect],
  );

  return (
    <Popover open={open} onOpenChange={handleOpenChange}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent
        align="end"
        className="w-[320px] p-0"
        onCloseAutoFocus={(e) => e.preventDefault()}
        onInteractOutside={(e) => {
          const target = e.target as HTMLElement | null;
          if (target?.closest("[data-prompt-versions-submenu]")) {
            e.preventDefault();
          }
        }}
      >
        <div className="px-1 pt-1">
          <SearchInput
            searchText={search}
            setSearchText={setSearch}
            placeholder="Search"
            variant="ghost"
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
              />
            ))
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
};

type PromptRowProps = {
  prompt: Prompt;
  onSelect: (selection: PromptLibrarySelection) => void;
};

const PromptRow: React.FC<PromptRowProps> = ({ prompt, onSelect }) => {
  const [hoverOpen, setHoverOpen] = useState(false);

  const latestStage = pickHighestStage(prompt.latest_version?.tags);
  const latestLabel = prompt.version_count > 0 ? `v${prompt.version_count}` : "";

  return (
    <HoverCard
      openDelay={120}
      closeDelay={120}
      open={hoverOpen}
      onOpenChange={setHoverOpen}
    >
      <HoverCardTrigger asChild>
        <div
          role="button"
          tabIndex={0}
          className="comet-body-s flex h-9 cursor-pointer items-center gap-2 rounded-md px-2 hover:bg-primary-foreground"
          onClick={() => onSelect({ promptId: prompt.id })}
        >
          <FileTerminal className="size-3.5 shrink-0 text-light-slate" />
          <span className="truncate">{prompt.name}</span>
          {latestLabel && (
            <span className="comet-body-xs ml-auto flex shrink-0 items-center gap-1 text-light-slate">
              {latestLabel}
              {latestStage && <StageTag value={latestStage} size="xs" />}
            </span>
          )}
          <ChevronRight className="size-3.5 shrink-0 text-light-slate" />
        </div>
      </HoverCardTrigger>
      <HoverCardContent
        side="left"
        align="start"
        sideOffset={8}
        className="w-[220px] p-1"
        data-prompt-versions-submenu=""
      >
        <PromptVersionsList
          promptId={prompt.id}
          activeVersionId={prompt.latest_version?.id}
          enabled={hoverOpen}
          onSelect={(versionId) => onSelect({ promptId: prompt.id, versionId })}
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
  const total = data?.total ?? versions.length;

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-3">
        <Spinner />
      </div>
    );
  }

  if (versions.length === 0) {
    return (
      <div className="comet-body-xs px-2 py-2 text-light-slate">
        No versions
      </div>
    );
  }

  return (
    <div className="max-h-[40vh] overflow-y-auto">
      {versions.map((version, idx) => {
        const label = `v${total - idx}`;
        const isActive = version.id === activeVersionId;
        const stage = pickHighestStage(version.tags);
        return (
          <div
            key={version.id}
            role="button"
            tabIndex={0}
            className={cn(
              "comet-body-s flex h-9 cursor-pointer items-center gap-2 rounded-md px-2 hover:bg-primary-foreground",
              isActive && "bg-primary-100 text-primary",
            )}
            onClick={() => onSelect(version.id)}
          >
            <span
              className={cn(
                "comet-body-s-accented shrink-0",
                isActive && "text-primary",
              )}
            >
              {label}
            </span>
            {stage && <StageTag value={stage} size="xs" />}
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
