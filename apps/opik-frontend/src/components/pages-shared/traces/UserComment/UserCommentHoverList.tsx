import { useCallback, useState } from "react";
import UserComment from "./UserComment";
import { Comment } from "@/types/comment";
import { Button } from "@/components/ui/button";
import { ArrowRight } from "lucide-react";
import {
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
} from "@/components/ui/hover-card";

type UserCommentHoverListProps = {
  commentsList: Comment[];
  onReply: () => void;
  className?: string;
  children: React.ReactNode;
};
const UserCommentHoverList: React.FC<UserCommentHoverListProps> = ({
  commentsList,
  onReply,
  className,
  children,
}) => {
  const [isOpen, setIsOpen] = useState(false);

  const onRefCreated = useCallback((ref: HTMLDivElement | null) => {
    if (!ref) return;

    ref.scrollTo({
      top: ref.scrollHeight,
    });
  }, []);

  const handleOnReply = () => {
    setIsOpen(false);
    onReply();
  };

  if (!commentsList.length) return <>{children}</>;

  return (
    <HoverCard open={isOpen} onOpenChange={setIsOpen}>
      <HoverCardTrigger asChild>
        <div className={className}>{children}</div>
      </HoverCardTrigger>
      <HoverCardContent className="w-[320px] p-0" collisionPadding={24}>
        <div
          className="relative size-full max-h-[40vh] max-w-[320px] overflow-auto p-1 pb-0"
          ref={onRefCreated}
        >
          <div>
            {commentsList.map((comment) => (
              <UserComment
                key={comment.id}
                comment={comment}
                size="sm"
                avatar={<UserComment.Avatar />}
                className="border-b border-slate-200 px-1.5 last:border-transparent"
                header={
                  <>
                    <UserComment.Username />
                    <UserComment.CreatedAt />
                  </>
                }
              >
                <UserComment.Text />
              </UserComment>
            ))}
          </div>

          <div className="sticky bottom-0 flex justify-end border-t border-slate-200 bg-popover py-1">
            <Button
              variant="ghost"
              className="comet-body-xs h-6 w-full justify-end gap-1 text-foreground"
              size="sm"
              onClick={handleOnReply}
            >
              Reply <ArrowRight className="size-3.5" />
            </Button>
          </div>
        </div>
      </HoverCardContent>
    </HoverCard>
  );
};

export default UserCommentHoverList;
