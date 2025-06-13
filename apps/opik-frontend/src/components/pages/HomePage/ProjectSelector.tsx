import React, { useCallback, useMemo, useState } from "react";
import { Plus } from "lucide-react";
import toLower from "lodash/toLower";

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
        <RemovableTag label="All projects" size="lg" />
      )}

      <DropdownMenu onOpenChange={openStateChangeHandler}>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="sm">
            More
            <Plus className="ml-1 size-4 shrink-0" />
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
              placeholder="Search"
              variant="ghost"
            ></SearchInput>
            <Separator className="mt-1" />
          </div>
          <div className="max-h-[calc(var(--radix-popper-available-height)-60px)] overflow-y-auto">
            {hasMoreProjects && (
              <div className="comet-body-xs px-4 py-2 text-muted-slate">
                Presenting the latest {LOADED_PROJECTS_COUNT} projects, sorted
                by recent activity.
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
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => setProjectIds([])}>
              All projects
            </DropdownMenuItem>
          </div>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
};

export default ProjectSelector;
