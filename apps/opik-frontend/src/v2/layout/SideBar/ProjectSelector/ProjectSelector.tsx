import React, { useCallback, useRef, useState } from "react";
import {
  Check,
  ChevronDown,
  MoreHorizontal,
  Pencil,
  Settings2,
  Trash,
} from "lucide-react";
import { Link, useNavigate } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import {
  Popover,
  PopoverAnchor,
  PopoverContent,
  PopoverTrigger,
} from "@/ui/popover";
import { Separator } from "@/ui/separator";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { SearchInput } from "@/shared/SearchInput/SearchInput";
import {
  setActiveProject,
  useActiveProjectInitializer,
} from "@/hooks/useActiveProjectInitializer";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { Spinner } from "@/ui/spinner";
import useProjectsList from "@/api/projects/useProjectsList";
import useProjectById from "@/api/projects/useProjectById";
import useProjectDeleteMutation from "@/api/projects/useProjectDeleteMutation";
import { usePermissions } from "@/contexts/PermissionsContext";
import { DEFAULT_PROJECT_NAME, Project } from "@/types/projects";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import AddEditProjectDialog from "@/v2/pages/ProjectsPage/AddEditProjectDialog";

const ProjectSelector: React.FC = () => {
  useActiveProjectInitializer();

  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const activeProjectId = useActiveProjectId();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();

  const { data: activeProject, isPending: isProjectPending } = useProjectById(
    { projectId: activeProjectId! },
    { enabled: !!activeProjectId },
  );

  const isLoading = !!activeProjectId && isProjectPending;

  const { data: projectsData } = useProjectsList(
    {
      workspaceName,
      search,
      page: 1,
      size: 50,
    },
    { enabled: open },
  );

  const handleSelect = useCallback(
    (projectId: string) => {
      setActiveProject(workspaceName, projectId);
      setOpen(false);
      setSearch("");
      navigate({
        to: "/$workspaceName/projects/$projectId/home",
        params: { workspaceName, projectId },
      });
    },
    [navigate, workspaceName],
  );

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverAnchor asChild>
        <div className="flex w-full items-center gap-1">
          {isLoading ? (
            <span className="flex flex-1 items-center gap-2 rounded-md px-2 py-1">
              <Spinner size="xs" />
              <span className="comet-body-s text-muted-slate">Loading…</span>
            </span>
          ) : activeProject ? (
            <Link
              to="/$workspaceName/projects/$projectId/home"
              params={{ workspaceName, projectId: activeProject.id }}
              activeOptions={{ exact: true }}
              className="comet-body-s-accented flex-1 truncate rounded-md px-2 py-1 text-left text-foreground hover:bg-primary-foreground data-[status=active]:bg-muted"
            >
              {activeProject.name}
            </Link>
          ) : (
            <span className="comet-body-s-accented flex-1 truncate rounded-md px-2 py-1 text-left text-muted-slate">
              Select project
            </span>
          )}
          <PopoverTrigger asChild>
            <button
              className={cn(
                "flex size-7 shrink-0 items-center justify-center rounded-md hover:bg-primary-foreground",
                open && "bg-primary-foreground",
              )}
            >
              <ChevronDown
                className={cn(
                  "size-3.5 text-muted-slate",
                  open && "rotate-180",
                )}
              />
            </button>
          </PopoverTrigger>
        </div>
      </PopoverAnchor>
      <PopoverContent
        align="start"
        side="bottom"
        className="w-[216px] p-1"
        sideOffset={4}
      >
        <div className="px-3 py-2">
          <span className="comet-body-s-accented text-foreground">
            Projects
          </span>
        </div>
        <Separator className="my-1" />
        <div className="px-1">
          <SearchInput
            searchText={search}
            setSearchText={setSearch}
            variant="ghost"
            size="sm"
          />
        </div>
        <Separator className="my-1" />
        <div className="max-h-[300px] overflow-auto">
          {projectsData?.content?.map((project) => (
            <ProjectItem
              key={project.id}
              project={project}
              isSelected={project.id === activeProjectId}
              onSelect={handleSelect}
              onDelete={
                project.id === activeProjectId
                  ? () => {
                      setActiveProject(workspaceName, null);
                      navigate({
                        to: "/$workspaceName/projects",
                        params: { workspaceName },
                      });
                    }
                  : undefined
              }
            />
          ))}
        </div>
        <Separator className="my-1" />
        <Button
          variant="ghost"
          size="sm"
          className="w-full justify-start text-primary"
          onClick={() => {
            setOpen(false);
            navigate({
              to: "/$workspaceName/projects",
              params: { workspaceName },
            });
          }}
        >
          <Settings2 className="mr-2 size-3.5" />
          Manage projects
        </Button>
      </PopoverContent>
    </Popover>
  );
};

interface ProjectItemProps {
  project: Project;
  isSelected: boolean;
  onSelect: (projectId: string) => void;
  onDelete?: () => void;
}

const ProjectItem: React.FC<ProjectItemProps> = ({
  project,
  isSelected,
  onSelect,
  onDelete,
}) => {
  const resetKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean | number>(false);

  const {
    permissions: { canCreateProjects, canDeleteProjects },
  } = usePermissions();

  const { mutate: deleteProject } = useProjectDeleteMutation();

  const handleDelete = useCallback(() => {
    deleteProject({ projectId: project.id }, { onSuccess: () => onDelete?.() });
  }, [project.id, deleteProject, onDelete]);

  const isDefaultProject = project.name === DEFAULT_PROJECT_NAME;
  const canDelete = canDeleteProjects && !isDefaultProject;
  const hasActions = canCreateProjects || canDelete;

  return (
    <>
      <AddEditProjectDialog
        key={`edit-${resetKeyRef.current}`}
        project={project}
        open={openDialog === 2}
        setOpen={setOpenDialog}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={openDialog === 1}
        setOpen={setOpenDialog}
        onConfirm={handleDelete}
        title="Delete project"
        description="Deleting a project will also remove all the traces and their data. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete project"
        confirmButtonVariant="destructive"
      />
      <div
        className={cn(
          "group flex cursor-pointer items-center gap-2 rounded-md px-3 py-1.5 hover:bg-primary-foreground",
          isSelected && "bg-muted",
        )}
        onClick={() => onSelect(project.id)}
      >
        <Check
          className={cn(
            "size-3.5 shrink-0",
            isSelected ? "text-foreground" : "invisible",
          )}
        />
        <span className="comet-body-s flex-1 truncate text-foreground">
          {project.name}
        </span>
        {hasActions && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild onClick={(e) => e.stopPropagation()}>
              <Button
                variant="minimal"
                size="icon-2xs"
                className="invisible shrink-0 group-hover:visible"
              >
                <MoreHorizontal className="size-3.5" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-40">
              {canCreateProjects && (
                <DropdownMenuItem
                  onClick={(e) => {
                    e.stopPropagation();
                    setOpenDialog(2);
                    resetKeyRef.current += 1;
                  }}
                >
                  <Pencil className="mr-2 size-3.5" />
                  Edit
                </DropdownMenuItem>
              )}
              {canCreateProjects && canDelete && <DropdownMenuSeparator />}
              {canDelete && (
                <DropdownMenuItem
                  variant="destructive"
                  onClick={(e) => {
                    e.stopPropagation();
                    setOpenDialog(1);
                    resetKeyRef.current += 1;
                  }}
                >
                  <Trash className="mr-2 size-3.5" />
                  Delete
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </div>
    </>
  );
};

export default ProjectSelector;
