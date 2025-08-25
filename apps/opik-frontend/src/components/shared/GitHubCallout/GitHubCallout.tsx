import React from "react";
import { Info, SquareArrowOutUpRight } from "lucide-react";

import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";

export interface GitHubCalloutProps {
  description?: string;
}

const GitHubCallout: React.FunctionComponent<GitHubCalloutProps> = ({
  description,
}) => {
  return (
    <Alert variant="callout" size="sm">
      <Info />
      <AlertDescription size="sm">
        {description} Open a
        <Button variant="link" size="3xs" asChild>
          <a
            href="https://github.com/comet-ml/opik/issues"
            target="_blank"
            rel="noreferrer"
          >
            GitHub ticket
            <SquareArrowOutUpRight className="ml-0.5 size-3 shrink-0" />
          </a>
        </Button>
        to let us know!
      </AlertDescription>
    </Alert>
  );
};

export default GitHubCallout;
