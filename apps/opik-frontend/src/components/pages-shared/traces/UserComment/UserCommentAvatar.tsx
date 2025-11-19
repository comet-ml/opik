import React from "react";
import { cva, VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";
import { getRandomColorByLabel } from "@/constants/colorVariants";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";

const userCommentAvatarVariants = cva(
  "border border-white font-medium text-white",
  {
    variants: {
      size: {
        default: "size-[20px] text-xs",
        sm: "size-[16px] text-[8px]",
      },
    },
    defaultVariants: {
      size: "default",
    },
  },
);

type CounterProps = VariantProps<typeof userCommentAvatarVariants> & {
  count: number;
  className?: string;
};
const Counter: React.FC<CounterProps> = ({ count, size, className }) => {
  return (
    <Avatar className={cn(userCommentAvatarVariants({ size }), className)}>
      <AvatarFallback className="bg-border text-muted-slate">
        +{count}
      </AvatarFallback>
    </Avatar>
  );
};

type Components = {
  Counter: typeof Counter;
};
type UserCommentAvatarProps = VariantProps<typeof userCommentAvatarVariants> & {
  username: string;
  className?: string;
};
const UserCommentAvatar: Components & React.FC<UserCommentAvatarProps> = ({
  size,
  username,
  className,
}) => {
  return (
    <Avatar
      style={
        {
          "--bg-avatar-color": getRandomColorByLabel(username),
        } as React.CSSProperties
      }
      className={cn(userCommentAvatarVariants({ size }), className)}
    >
      <AvatarFallback className="grid place-items-center bg-[var(--bg-avatar-color)] leading-none will-change-transform">
        {username.charAt(0).toUpperCase()}
      </AvatarFallback>
    </Avatar>
  );
};

UserCommentAvatar.Counter = Counter;

export default UserCommentAvatar;
