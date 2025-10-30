import React from "react";
import { SquareArrowOutUpRight } from "lucide-react";

import { Button } from "@/components/ui/button";
import { buildDocsUrl, cn } from "@/lib/utils";
import { Explainer } from "@/types/shared";

type ExplainerDescriptionProps = {
  size?: "sm" | "md";
  className?: string;
  isMinimalLink?: boolean;
  iconSize?: string;
} & Omit<Explainer, "id">;

const ExplainerDescription: React.FC<ExplainerDescriptionProps> = ({
  title,
  description,
  docLink,
  docHash,
  isMinimalLink = false,
  iconSize = "size-4",
  className,
}) => {
  return (
    <div className={cn(className)}>
      {title && (
        <h5 className="comet-body-s-accented mb-2 truncate text-foreground">
          {title}
        </h5>
      )}
      <span
        className={cn(
          "comet-body-s whitespace-pre-wrap break-words text-muted-slate",
          isMinimalLink && "text-light-slate",
        )}
      >
        {description}
      </span>
      {docLink && (
        <Button
          variant={isMinimalLink ? "minimal" : "link"}
          className="h-5 px-1"
          asChild
        >
          <a
            href={buildDocsUrl(docLink, docHash)}
            target="_blank"
            rel="noreferrer"
          >
            Read more
            <SquareArrowOutUpRight className={cn("ml-1 shrink-0", iconSize)} />
          </a>
        </Button>
      )}
    </div>
  );
};

export default ExplainerDescription;
