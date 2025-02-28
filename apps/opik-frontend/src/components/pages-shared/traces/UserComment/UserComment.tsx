import { CommentItem } from "@/types/comment";
import React, { createContext, useContext, useState } from "react";
import UserCommentAvatar from "./UserCommentAvatar";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { MoreHorizontal, Pencil, Trash } from "lucide-react";
import { cn } from "@/lib/utils";
import UserCommentForm, { UserCommentFormProps } from "./UserCommentForm";
import { formatDate, getTimeFromNow } from "@/lib/date";
import {
  createdAtStyleVariants,
  rootStyleVariants,
  textStyleVariants,
  usernameStyleVariants,
} from "./styles";
import { isUndefined } from "lodash";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type UserCommentContextType = {
  size?: "default" | "sm";
  userName?: string;
  comment: CommentItem;
  isLocalComment: boolean;
  isEditMode: boolean;
  setIsEditMode: (v: boolean) => void;
};

const UserCommentContext = createContext<UserCommentContextType | null>(null);

export const isLocalCommentCheck = (createdBy: string) =>
  isUndefined(createdBy) || createdBy === "admin";

export const useUserCommentContext = () => {
  const context = useContext(UserCommentContext);
  if (!context) {
    throw new Error(
      "UserComment components must be used within UserComment.Root",
    );
  }
  return context;
};

const Text = () => {
  const { comment, size, isEditMode } = useUserCommentContext();

  if (isEditMode) return null;

  return <div className={textStyleVariants({ size })}>{comment.text}</div>;
};

const Username = () => {
  const { comment, size, isLocalComment } = useUserCommentContext();

  if (isLocalComment) return;

  return (
    <div className={usernameStyleVariants({ size })}>{comment.created_by}</div>
  );
};

const CreatedAt = () => {
  const { comment, size } = useUserCommentContext();
  const formattedDate = formatDate(comment.created_at);
  const timeFromNow = getTimeFromNow(comment.created_at);

  return (
    <TooltipWrapper content={formattedDate}>
      <div className={createdAtStyleVariants({ size })}>{timeFromNow}</div>
    </TooltipWrapper>
  );
};

type MenuProps = {
  children: React.ReactNode;
};
const Menu: React.FC<MenuProps> = ({ children }) => {
  const [open, setOpen] = useState(false);
  const { isEditMode, userName, comment } = useUserCommentContext();

  const isUserOwner =
    isUndefined(userName) ||
    isUndefined(comment.created_by) ||
    userName === comment.created_by;

  if (!isUserOwner) return;

  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger asChild>
        <Button
          variant="outline"
          size="icon-xs"
          className={cn(
            "opacity-0 hover:opacity-100 group-hover:opacity-100 -mt-1 -mb-1",
            open && "opacity-100",
            isEditMode && "opacity-0 hover:opacity-0 group-hover:opacity-0",
          )}
        >
          <span className="sr-only">Actions menu</span>
          <MoreHorizontal />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-52">
        {children}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

const MenuEditItem = () => {
  const { setIsEditMode } = useUserCommentContext();

  const handleOnEdit = () => {
    setIsEditMode(true);
  };

  return (
    <DropdownMenuItem onClick={handleOnEdit}>
      <Pencil className="mr-2 size-4" />
      Edit
    </DropdownMenuItem>
  );
};

type MenuDeleteItemProps = {
  onDelete: (commentId: string) => void;
};
const MenuDeleteItem: React.FC<MenuDeleteItemProps> = ({ onDelete }) => {
  const { comment } = useUserCommentContext();

  const handleOnDelete = () => {
    onDelete(comment.id);
  };

  return (
    <DropdownMenuItem onClick={handleOnDelete}>
      <Trash className="mr-2 size-4" />
      Delete
    </DropdownMenuItem>
  );
};

type FormProps = {
  onSubmit: (commentId: string, text: string) => void;
};
const Form: React.FC<FormProps> = ({ onSubmit }) => {
  const { comment, isEditMode, setIsEditMode } = useUserCommentContext();

  const handleSubmit: UserCommentFormProps["onSubmit"] = (data) => {
    onSubmit(comment.id, data.commentText);
    setIsEditMode(false);
  };

  if (!isEditMode) return null;

  return (
    <UserCommentForm
      onSubmit={handleSubmit}
      commentText={comment.text}
      className="mt-1.5"
      actions={
        <>
          <UserCommentForm.CancelButton onClick={() => setIsEditMode(false)} />
          <UserCommentForm.SubmitButton editMode />
        </>
      }
    >
      <UserCommentForm.TextareaField />
    </UserCommentForm>
  );
};

const Avatar = () => {
  const { comment, size, isLocalComment } = useUserCommentContext();

  if (isLocalComment) return;

  return <UserCommentAvatar username={comment.created_by} size={size} />;
};

type UserCommentComponents = {
  Text: typeof Text;
  Username: typeof Username;
  CreatedAt: typeof CreatedAt;
  Menu: typeof Menu;
  MenuEditItem: typeof MenuEditItem;
  MenuDeleteItem: typeof MenuDeleteItem;
  Form: typeof Form;
  Avatar: typeof Avatar;
};

type UserCommentProps = {
  comment: CommentItem;
  size?: "default" | "sm";
  avatar?: React.ReactNode;
  header?: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
  userName?: string;
  children: React.ReactNode;
};
const UserComment: UserCommentComponents & React.FC<UserCommentProps> = ({
  size,
  comment,
  avatar,
  header,
  actions,
  className,
  userName,
  children,
}) => {
  const [isEditMode, setIsEditMode] = useState(false);
  const isLocalComment = isLocalCommentCheck(comment.created_by);

  return (
    <UserCommentContext.Provider
      value={{
        size,
        comment,
        isEditMode,
        setIsEditMode,
        userName,
        isLocalComment,
      }}
    >
      <div className={cn(rootStyleVariants({ size }), className)}>
        {avatar}
        <div className="flex min-w-0 flex-1 flex-col text-foreground">
          <div className="flex justify-between">
            <div
              className={cn(
                "flex min-w-0 flex-1 gap-1 pr-2",
                size === "sm" ? "min-h-[16px]" : "min-h-[20px]",
              )}
            >
              {header}
            </div>
            {actions}
          </div>
          <div className="w-full">{children}</div>
        </div>
      </div>
    </UserCommentContext.Provider>
  );
};

UserComment.Text = Text;
UserComment.Username = Username;
UserComment.CreatedAt = CreatedAt;
UserComment.Menu = Menu;
UserComment.MenuEditItem = MenuEditItem;
UserComment.MenuDeleteItem = MenuDeleteItem;
UserComment.Form = Form;
UserComment.Avatar = Avatar;

export default UserComment;
