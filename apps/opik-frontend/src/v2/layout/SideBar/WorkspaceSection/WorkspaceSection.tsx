import React from "react";
import { cn } from "@/lib/utils";
import useAppStore from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";
import SidebarMenuItem from "@/v2/layout/SideBar/MenuItem/SidebarMenuItem";
import { getWorkspaceMenuItems } from "@/v2/layout/SideBar/helpers/getMenuItems";
import { usePermissions } from "@/contexts/PermissionsContext";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { calculateWorkspaceName } from "@/lib/utils";

interface WorkspaceSectionProps {
  expanded: boolean;
}

const WorkspaceSection: React.FC<WorkspaceSectionProps> = ({ expanded }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const WorkspaceSelectorComponent = usePluginsStore(
    (state) => state.WorkspaceSelector,
  );
  const {
    permissions: { canViewDashboards },
  } = usePermissions();

  const menuGroups = getWorkspaceMenuItems({ canViewDashboards });
  const displayName = calculateWorkspaceName(workspaceName);

  const renderWorkspaceSelector = () => {
    if (WorkspaceSelectorComponent) {
      return <WorkspaceSelectorComponent />;
    }

    if (!expanded) {
      return (
        <TooltipWrapper content={displayName} side="right">
          <div className="comet-body-s-accented flex w-9 items-center justify-center truncate rounded-md py-1 text-foreground">
            {displayName.charAt(0).toUpperCase()}
          </div>
        </TooltipWrapper>
      );
    }

    return (
      <div className="comet-body-s-accented truncate rounded-md px-2 py-1 text-foreground">
        {displayName}
      </div>
    );
  };

  return (
    <div className="flex flex-col px-3 py-2">
      {expanded && (
        <div className="comet-body-s-accented truncate px-2 py-1 text-light-slate">
          Workspace
        </div>
      )}

      {renderWorkspaceSelector()}

      <ul
        className={cn(
          "flex flex-col text-muted-slate",
          !expanded && "items-center",
        )}
      >
        {menuGroups.flatMap((group) =>
          group.items.map((item) => (
            <SidebarMenuItem key={item.id} item={item} expanded={expanded} />
          )),
        )}
      </ul>
    </div>
  );
};

export default WorkspaceSection;
