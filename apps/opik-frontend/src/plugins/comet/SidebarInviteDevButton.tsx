import React, { useState } from "react";
import { UserPlus } from "lucide-react";
import useWorkspace from "@/plugins/comet/useWorkspace";
import SidebarMenuItem, {
  MENU_ITEM_TYPE,
} from "@/components/layout/SideBar/MenuItem/SidebarMenuItem";
import {
  DropdownMenu,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
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

  if (!workspace) {
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
