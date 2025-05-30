import React from "react";
import { SquareArrowOutUpRight } from "lucide-react";

import { Button } from "@/components/ui/button";
import { buildDocsUrl, cn } from "@/lib/utils";
import { Explainer } from "@/types/shared";

type ExplainerDescriptionProps = {
  size?: "sm" | "md";
  className?: string;
} & Explainer;

const ExplainerDescription: React.FC<ExplainerDescriptionProps> = ({
  title,
  description,
  docLink,
  docHash,
  size = "md",
  className,
}) => {
  return (
    <div className={cn(className)}>
      {title && (
        <h5
          className={cn(
            "mb-2 truncate text-foreground",
            size === "md" ? "comet-body-accented" : "comet-body-s-accented",
          )}
        >
          {title}
        </h5>
      )}
      <span
        className={cn(
          "whitespace-pre-wrap break-words text-muted-slate",
          size === "md" ? "comet-body" : "comet-body-s",
        )}
      >
        {description}
      </span>
      {docLink && (
        <Button variant="link" className="px-1" asChild>
          <a
            href={buildDocsUrl(docLink, docHash)}
            target="_blank"
            rel="noreferrer"
          >
            Read more
            <SquareArrowOutUpRight className="ml-1 size-4 shrink-0" />
          </a>
        </Button>
      )}
    </div>
  );
};

export default ExplainerDescription;
