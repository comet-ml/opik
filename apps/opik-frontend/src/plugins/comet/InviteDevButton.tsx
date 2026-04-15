import { UserPlus } from "lucide-react";
import { Button, type ButtonProps } from "@/ui/button";
import useInviteMembersURL from "@/plugins/comet/useInviteMembersURL";

export type InviteDevButtonProps = {
  onClick?: () => void;
  size?: ButtonProps["size"];
};

const InviteDevButton: React.FC<InviteDevButtonProps> = ({ onClick, size }) => {
  const inviteMembersURL = useInviteMembersURL();

  if (!inviteMembersURL) {
    return null;
  }

  return (
    <Button
      className="flex-1"
      variant="outline"
      size={size}
      onClick={onClick}
      asChild
    >
      <a href={inviteMembersURL} target="_blank" rel="noopener noreferrer">
        <UserPlus className="mr-2 size-4" />
        <span>Invite a developer</span>
      </a>
    </Button>
  );
};

export default InviteDevButton;
