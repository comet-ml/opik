import React, { useMemo } from "react";
import isNumber from "lodash/isNumber";
import GitHubIcon from "@/icons/github.svg?react";

import useGitHubStarts from "@/api/external/useGitHubStarts";
import { Button } from "@/ui/button";
import { formatNumberInK } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

const GitHubStarButton: React.FC = () => {
  const { data } = useGitHubStarts({});

  const starCount = useMemo(() => {
    const count = data?.stargazers_count;
    return isNumber(count) ? formatNumberInK(count) : "18.5k";
  }, [data?.stargazers_count]);

  return (
    <TooltipWrapper content={`Star Opik on GitHub and join ${starCount} users`}>
      <Button variant="ghost" size="icon-2xs" asChild>
        <a
          href="https://github.com/comet-ml/opik"
          target="_blank"
          rel="noreferrer"
        >
          <GitHubIcon className="size-3" />
        </a>
      </Button>
    </TooltipWrapper>
  );
};

export default GitHubStarButton;
