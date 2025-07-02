import React from "react";
import {
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
} from "@/components/ui/hover-card";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";

type TagsHoverCardProps = {
  tags: string[];
  children: React.ReactNode;
};
const TagsHoverCard: React.FC<TagsHoverCardProps> = ({ tags, children }) => {
  return (
    <HoverCard openDelay={500}>
      <HoverCardTrigger asChild>{children}</HoverCardTrigger>
      <HoverCardContent
        side="top"
        align="start"
        className="w-[320px] border border-border px-1.5 py-2"
        collisionPadding={24}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex size-full max-h-[40vh] max-w-[320px] flex-wrap gap-1.5 overflow-y-auto">
          {tags.map((tag) => (
            <ColoredTag key={tag} label={tag} />
          ))}
        </div>
      </HoverCardContent>
    </HoverCard>
  );
};

export default TagsHoverCard;
