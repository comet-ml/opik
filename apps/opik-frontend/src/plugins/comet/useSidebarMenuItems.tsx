import { MENU_ITEM_TYPE, MenuItem } from "@/components/layout/SideBar/SideBar";
import useInviteMembersURL from "@/plugins/comet/useInviteMembersURL";
import { UserPlus } from "lucide-react";

const useSidebarMenuItems = (): MenuItem[] => {
  const inviteMembersURL = useInviteMembersURL();

  if (!inviteMembersURL) return [];

  return [
    {
      id: "inviteTeamMember",
      type: MENU_ITEM_TYPE.button,
      icon: UserPlus,
      label: "Invite a teammate",
      onClick: () => {
        window.open(inviteMembersURL, "_blank");
      },
    },
  ];
};

export default useSidebarMenuItems;
