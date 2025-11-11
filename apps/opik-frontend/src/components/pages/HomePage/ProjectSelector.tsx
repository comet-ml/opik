import React, { useCallback, useMemo, useState } from "react";
import { FilterIcon } from "lucide-react";
import toLower from "lodash/toLower";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuCustomCheckboxItem,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { Separator } from "@/components/ui/separator";
import { Project } from "@/types/projects";
import RemovableTag from "@/components/shared/RemovableTag/RemovableTag";

export const LOADED_PROJECTS_COUNT = 100;
const MAX_SELECTED_PROJECTS = 10;

type ProjectSelectorProps = {
  projectIds: string[];
  setProjectIds: (projectIds: string[]) => void;
  projects: Project[];
  totalProjects: number;
};

const ProjectSelector: React.FC<ProjectSelectorProps> = ({
  projectIds,
  setProjectIds,
  projects,
  totalProjects,
}) => {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");

  const selectedProjects = useMemo(() => {
    if (!projectIds || projectIds.length === 0) {
      return [];
    }
    return projects.filter((p) => projectIds.includes(p.id));
  }, [projectIds, projects]);

  const filteredProjects = useMemo(() => {
    return projects.filter((p) => toLower(p.name).includes(toLower(search)));
  }, [projects, search]);

  const hasMoreProjects = Boolean(totalProjects > LOADED_PROJECTS_COUNT);

  const openStateChangeHandler = useCallback((open: boolean) => {
    // need to clear state when it closed
    if (!open) setSearch("");
  }, []);

  return (
    <div className="flex min-w-1 flex-auto flex-wrap items-center gap-2">
      <DropdownMenu onOpenChange={openStateChangeHandler}>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="sm">
            <FilterIcon className="mr-1.5 size-3.5" />
            {t("home.overallPerformance.filterProjects")}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          className="relative max-w-72 p-0 pt-12"
          align="end"
        >
          <div
            className="absolute inset-x-1 top-1 h-11"
            onKeyDown={(e) => e.stopPropagation()}
          >
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              placeholder={t("home.overallPerformance.search")}
              variant="ghost"
            ></SearchInput>
            <Separator className="mt-1" />
          </div>
          <div className="max-h-[calc(var(--radix-popper-available-height)-60px)] overflow-y-auto overflow-x-hidden pb-1">
            {hasMoreProjects && (
              <div className="comet-body-xs px-4 py-2 text-muted-slate">
                {t("home.overallPerformance.presentingLatest", { count: LOADED_PROJECTS_COUNT })}
              </div>
            )}
            {filteredProjects.map(({ name, id }) => (
              <DropdownMenuCustomCheckboxItem
                key={id}
                checked={projectIds.includes(id)}
                onSelect={(event) => event.preventDefault()}
                onCheckedChange={() =>
                  setProjectIds(
                    projectIds.includes(id)
                      ? projectIds.filter((v) => v !== id)
                      : [...projectIds, id],
                  )
                }
                disabled={
                  projectIds.length >= MAX_SELECTED_PROJECTS &&
                  !projectIds.includes(id)
                }
              >
                {name}
              </DropdownMenuCustomCheckboxItem>
            ))}
            {filteredProjects.length === 0 && Boolean(search) && (
              <div className="comet-body-s flex h-32 w-56 items-center justify-center text-muted-slate">
                {t("home.overallPerformance.noSearchResults")}
              </div>
            )}

            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => setProjectIds([])}>
              {t("home.overallPerformance.allProjects")}
            </DropdownMenuItem>
          </div>
        </DropdownMenuContent>
      </DropdownMenu>
      <Separator orientation="vertical" className="mx-1 h-4" />
      {selectedProjects.length > 0 ? (
        selectedProjects.map((p) => (
          <RemovableTag
            key={p.name}
            label={p.name}
            size="lg"
            className="max-w-56"
            onDelete={() => {
              setProjectIds(projectIds.filter((v) => v !== p.id) ?? null);
            }}
          />
        ))
      ) : (
        <RemovableTag label={t("home.overallPerformance.allProjects")} size="lg" />
      )}
    </div>
  );
};

export default ProjectSelector;
