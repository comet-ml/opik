import React from "react";
import { Avatar, AvatarFallback } from "@/ui/avatar";
import { resolveColor } from "@/lib/colorVariants";
import { cn } from "@/lib/utils";

interface UserAvatarProps {
  name: string;
  className?: string;
}

const UserAvatar: React.FC<UserAvatarProps> = ({ name, className }) => (
  <Avatar
    style={{ "--bg-avatar-color": resolveColor(name) } as React.CSSProperties}
    className={cn("size-6", className)}
  >
    <AvatarFallback className="grid place-items-center bg-[var(--bg-avatar-color)] text-[10px] font-medium leading-none text-white">
      {(name || "?").charAt(0).toUpperCase()}
    </AvatarFallback>
  </Avatar>
);

export default UserAvatar;
