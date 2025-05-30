import React from "react";
import { Info, SquareArrowOutUpRight } from "lucide-react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";
import { Explainer } from "@/types/shared";

type ExplainerCalloutProps = {
  className?: string;
  Icon?: React.ComponentType<React.SVGProps<SVGSVGElement>>;
} & Explainer;

const ExplainerCallout: React.FC<ExplainerCalloutProps> = ({
  title,
  description,
  docLink,
  docHash,
  className,
  Icon = Info,
}) => {
  return (
    <Alert variant="callout" size="sm" className={className}>
      <Icon />
      {title && <AlertTitle size="sm">{title}</AlertTitle>}
      <AlertDescription size="sm">
        {description}
        {docLink && (
          <Button variant="link" size="3xs" asChild>
            <a
              href={buildDocsUrl(docLink, docHash)}
              target="_blank"
              rel="noreferrer"
            >
              Read more
              <SquareArrowOutUpRight className="ml-0.5 size-3 shrink-0" />
            </a>
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
};

export default ExplainerCallout;
