import React from "react";
import { useNavigate } from "@tanstack/react-router";
import { Undo2 } from "lucide-react";
import { useActiveWorkspaceName, useActiveProjectId } from "@/store/AppStore";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import { calculateWorkspaceName } from "@/lib/utils";
import usePluginsStore from "@/store/PluginsStore";
import useProjectById from "@/api/projects/useProjectById";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import SidebarMenuItem from "@/v2/layout/SideBar/MenuItem/SidebarMenuItem";
import GitHubStarListItem from "@/v2/layout/SideBar/GitHubStarListItem/GitHubStarListItem";
import { getWorkspaceSidebarMenuItems } from "@/v2/layout/SideBar/helpers/getMenuItems";
import { usePermissions } from "@/contexts/PermissionsContext";

interface WorkspaceSidebarContentProps {
  expanded: boolean;
}

const WorkspaceSidebarContent: React.FC<WorkspaceSidebarContentProps> = ({
  expanded,
}) => {
  const navigate = useNavigate();
  const workspaceName = useActiveWorkspaceName();
  const activeProjectId = useActiveProjectId();
  const SidebarWorkspaceSelectorComponent = usePluginsStore(
    (state) => state.SidebarWorkspaceSelector,
  );
  const {
    permissions: { canViewDashboards },
  } = usePermissions();

  const displayName = calculateWorkspaceName(workspaceName);

  const { data: activeProject } = useProjectById(
    { projectId: activeProjectId! },
    { enabled: !!activeProjectId },
  );

  const menuGroups = getWorkspaceSidebarMenuItems({ canViewDashboards });

  const workspaceSelector = SidebarWorkspaceSelectorComponent ? (
    <SidebarWorkspaceSelectorComponent expanded={expanded} />
  ) : expanded ? (
    <div className="comet-body-s-accented truncate rounded-md px-2 py-1 text-foreground">
      {displayName}
    </div>
  ) : null;

  const handleBackToProject = () => {
    if (activeProjectId) {
      navigate({
        to: "/$workspaceName/projects/$projectId/home",
        params: { workspaceName, projectId: activeProjectId },
      });
    } else {
      navigate({
        to: "/$workspaceName",
        params: { workspaceName },
      });
    }
  };

  const backButtonLabel = activeProject
    ? `Back to ${activeProject.name}`
    : "Back to project";

  const backButton = expanded ? (
    <Button
      variant="outline"
      size="xs"
      className="w-full justify-start gap-1"
      onClick={handleBackToProject}
    >
      <Undo2 className="size-3 shrink-0" />
      <span className="truncate">{backButtonLabel}</span>
    </Button>
  ) : (
    <Button variant="outline" size="icon-xs" onClick={handleBackToProject}>
      <Undo2 />
    </Button>
  );

  return (
    <>
      <div className="flex min-h-0 flex-1 flex-col overflow-auto">
        {expanded && (
          <div className="comet-body-xs-accented truncate px-2 py-1 text-light-slate">
            Workspace
          </div>
        )}
        {workspaceSelector}
        <ul className="mt-2 flex flex-col">
          {menuGroups.flatMap((group) =>
            group.items.map((item) => (
              <SidebarMenuItem key={item.id} item={item} expanded={expanded} />
            )),
          )}
        </ul>
      </div>

      <div className="shrink-0 pt-2">
        {expanded ? (
          backButton
        ) : (
          <TooltipWrapper
            content={backButtonLabel}
            side="right"
            delayDuration={0}
          >
            {backButton}
          </TooltipWrapper>
        )}
        <Separator className="my-4" />
        <ul className="flex flex-col">
          <GitHubStarListItem expanded={expanded} />
        </ul>
      </div>
    </>
  );
};

export default WorkspaceSidebarContent;
