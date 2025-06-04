import React from "react";
import { SquareArrowOutUpRight } from "lucide-react";

import { Button } from "@/components/ui/button";
import { buildDocsUrl, cn } from "@/lib/utils";
import { Explainer } from "@/types/shared";

type ExplainerDescriptionProps = {
  size?: "sm" | "md";
  className?: string;
} & Omit<Explainer, "id">;

const ExplainerDescription: React.FC<ExplainerDescriptionProps> = ({
  title,
  description,
  docLink,
  docHash,
  className,
}) => {
  return (
    <div className={cn(className)}>
      {title && (
        <h5 className="comet-body-s-accented mb-2 truncate text-foreground">
          {title}
        </h5>
      )}
      <span className="comet-body-s whitespace-pre-wrap break-words text-muted-slate">
        {description}
      </span>
      {docLink && (
        <Button variant="link" className="h-5 px-1" asChild>
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
