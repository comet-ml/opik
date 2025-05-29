import React from "react";
import { CircleHelp, Info, SquareArrowOutUpRight } from "lucide-react";

import { buildDocsUrl, cn } from "@/lib/utils";
import { Explainer } from "@/types/shared";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type ExplainerIconProps = {
  className?: string;
} & Explainer;

const ExplainerIcon: React.FC<ExplainerIconProps> = ({
  type = "info",
  description,
  docLink,
  docHash,
  className,
}) => {
  const Icon = type === "info" ? Info : CircleHelp;
  return (
    <TooltipWrapper
      stopClickPropagation
      content={
        <>
          {description}
          {docLink && (
            <Button variant="link" size="3xs" asChild>
              <a
                href={buildDocsUrl(docLink, docHash)}
                target="_blank"
                rel="noreferrer"
              >
                Raad more
                <SquareArrowOutUpRight className="ml-0.5 size-3 shrink-0" />
              </a>
            </Button>
          )}
        </>
      }
    >
      <Icon
        className={cn("size-3.5 shrink-0 text-light-slate", className)}
        onClick={(e) => e.stopPropagation()}
      />
    </TooltipWrapper>
  );
};

export default ExplainerIcon;
