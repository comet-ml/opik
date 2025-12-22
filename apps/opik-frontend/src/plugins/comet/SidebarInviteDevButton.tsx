import React, { useState } from "react";
import { UserPlus } from "lucide-react";
import useWorkspace from "@/plugins/comet/useWorkspace";
import useInviteMembersURL from "@/plugins/comet/useInviteMembersURL";
import useUserPermission from "@/plugins/comet/useUserPermission";
import SidebarMenuItem, {
  MENU_ITEM_TYPE,
} from "@/components/layout/SideBar/MenuItem/SidebarMenuItem";
import {
  DropdownMenu,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import InviteUsersPopover from "./InviteUsersPopover";

export type SidebarInviteDevButtonProps = {
  expanded: boolean;
};

const SidebarInviteDevButton: React.FC<SidebarInviteDevButtonProps> = ({
  expanded,
}) => {
  const [open, setOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const workspace = useWorkspace();
  const inviteMembersURL = useInviteMembersURL();
  const isCollaboratorsTabEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.COLLABORATORS_TAB_ENABLED,
  );

  const { canInviteMembers } = useUserPermission();

  if (!workspace) {
    return null;
  }

  if (!isCollaboratorsTabEnabled) {
    if (!inviteMembersURL) {
      return null;
    }

    return (
      <SidebarMenuItem
        item={{
          id: "inviteTeamMember",
          icon: UserPlus,
          label: "Invite a teammate",
          type: MENU_ITEM_TYPE.link,
          path: inviteMembersURL,
        }}
        expanded={expanded}
        compact
      />
    );
  }

  if (!canInviteMembers) {
    return null;
  }

  const handleClose = () => {
    setOpen(false);
    setSearchQuery("");
  };

  return (
    <DropdownMenu
      open={open}
      onOpenChange={(open) => {
        setOpen(open);
        if (!open) {
          setSearchQuery("");
        }
      }}
    >
      <DropdownMenuTrigger asChild>
        <div>
          <SidebarMenuItem
            item={{
              id: "inviteTeamMember",
              icon: UserPlus,
              label: "Invite a teammate",
              type: MENU_ITEM_TYPE.button,
            }}
            expanded={expanded}
            compact
          />
        </div>
      </DropdownMenuTrigger>
      <InviteUsersPopover
        searchQuery={searchQuery}
        setSearchQuery={setSearchQuery}
        onClose={handleClose}
        side="right"
      />
    </DropdownMenu>
  );
};

export default SidebarInviteDevButton;
