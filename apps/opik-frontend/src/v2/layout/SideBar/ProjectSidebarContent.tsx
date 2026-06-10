import React from "react";
import { useActiveWorkspaceName } from "@/store/AppStore";
import SideBarMenuItems from "@/v2/layout/SideBar/SideBarMenuItems";
import ProjectSelector from "@/v2/layout/SideBar/ProjectSelector/ProjectSelector";
import GitHubStarListItem from "@/v2/layout/SideBar/GitHubStarListItem/GitHubStarListItem";
import SidebarMenuItem from "@/v2/layout/SideBar/MenuItem/SidebarMenuItem";
import { getWorkspaceMenuItems } from "@/v2/layout/SideBar/helpers/getMenuItems";
import { Separator } from "@/ui/separator";
import { cn } from "@/lib/utils";

interface ProjectSidebarContentProps {
  expanded: boolean;
}

const ProjectSidebarContent: React.FC<ProjectSidebarContentProps> = ({
  expanded,
}) => {
  useActiveWorkspaceName();

  const workspaceItems = getWorkspaceMenuItems();

  return (
    <>
      <ProjectSelector expanded={expanded} />
      <Separator className={cn("my-2", !expanded && "w-auto mx-1")} />
      <div className="flex min-h-0 flex-1 flex-col overflow-auto">
        <ul className={cn("flex flex-col", !expanded && "gap-1")}>
          <SideBarMenuItems expanded={expanded} />
        </ul>
      </div>

      <div className="shrink-0 pt-2">
        <Separator
          className={cn(expanded ? "mb-3" : "mb-1", !expanded && "mx-1 w-auto")}
        />
        <ul className={cn("flex flex-col", !expanded && "gap-1")}>
          {workspaceItems.flatMap((group) =>
            group.items.map((item) => (
              <SidebarMenuItem key={item.id} item={item} expanded={expanded} />
            )),
          )}
        </ul>
        <ul className={cn("mt-2 flex flex-col", !expanded && "gap-1")}>
          <GitHubStarListItem expanded={expanded} />
        </ul>
      </div>
    </>
  );
};

export default ProjectSidebarContent;
