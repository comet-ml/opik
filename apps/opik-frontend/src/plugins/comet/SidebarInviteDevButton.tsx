import React from "react";
import { UserPlus } from "lucide-react";
import useInviteMembersURL from "@/plugins/comet/useInviteMembersURL";
import SidebarMenuItem, {
  MENU_ITEM_TYPE,
} from "@/components/layout/SideBar/MenuItem/SidebarMenuItem";

export type SidebarInviteDevButtonProps = {
  expanded: boolean;
};

const SidebarInviteDevButton: React.FC<SidebarInviteDevButtonProps> = ({
  expanded,
}) => {
  const inviteMembersURL = useInviteMembersURL();

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
    />
  );
};

export default SidebarInviteDevButton;
