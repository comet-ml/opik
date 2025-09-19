import React from "react";
import { Info, SquareArrowOutUpRight, X } from "lucide-react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { buildDocsUrl, cn } from "@/lib/utils";
import { Explainer } from "@/types/shared";
import useLocalStorageState from "use-local-storage-state";

type ExplainerCalloutProps = {
  className?: string;
  Icon?: React.ComponentType<React.SVGProps<SVGSVGElement>>;
  isDismissable?: boolean;
} & Explainer;

const ExplainerCallout: React.FC<ExplainerCalloutProps> = ({
  id,
  title,
  description,
  docLink,
  docHash,
  className,
  Icon = Info,
  isDismissable = true,
}) => {
  const [isShown, setIsShown] = useLocalStorageState<boolean>(
    `explainer-callout-${id}`,
    {
      defaultValue: true,
    },
  );

  if (!isShown) return null;

  return (
    <Alert
      variant="callout"
      size="sm"
      className={cn(isDismissable ? "pr-10" : "pr-4", className)}
    >
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
      {isDismissable && (
        <Button
          variant="minimal"
          size="icon-sm"
          onClick={() => setIsShown(false)}
          className="absolute right-1 top-1 !p-0"
        >
          <X />
        </Button>
      )}
    </Alert>
  );
};

export default ExplainerCallout;
