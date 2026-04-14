import React from "react";
import { useActiveWorkspaceName } from "@/store/AppStore";
import SideBarMenuItems from "@/v2/layout/SideBar/SideBarMenuItems";
import ProjectSelector from "@/v2/layout/SideBar/ProjectSelector/ProjectSelector";
import GitHubStarListItem from "@/v2/layout/SideBar/GitHubStarListItem/GitHubStarListItem";
import SidebarMenuItem from "@/v2/layout/SideBar/MenuItem/SidebarMenuItem";
import { getWorkspaceMenuItems } from "@/v2/layout/SideBar/helpers/getMenuItems";
import { usePermissions } from "@/contexts/PermissionsContext";
import { Separator } from "@/ui/separator";

interface ProjectSidebarContentProps {
  expanded: boolean;
}

const ProjectSidebarContent: React.FC<ProjectSidebarContentProps> = ({
  expanded,
}) => {
  useActiveWorkspaceName();
  const {
    permissions: { canViewDashboards },
  } = usePermissions();

  const workspaceItems = getWorkspaceMenuItems({ canViewDashboards });

  return (
    <>
      <ProjectSelector expanded={expanded} />
      {expanded && <Separator className="my-2" />}
      <div className="flex min-h-0 flex-1 flex-col overflow-auto">
        <ul className="flex flex-col">
          <SideBarMenuItems expanded={expanded} />
        </ul>
      </div>

      <div className="shrink-0 pt-2">
        <Separator className="mb-3" />
        <ul className="flex flex-col">
          {workspaceItems.flatMap((group) =>
            group.items.map((item) => (
              <SidebarMenuItem key={item.id} item={item} expanded={expanded} />
            )),
          )}
        </ul>
        <ul className="mt-2 flex flex-col">
          <GitHubStarListItem expanded={expanded} />
        </ul>
      </div>
    </>
  );
};

export default ProjectSidebarContent;
