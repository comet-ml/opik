import React, { useMemo } from "react";
import isNumber from "lodash/isNumber";
import GitHubIcon from "@/icons/github.svg?react";

import useGitHubStarts from "@/api/external/useGitHubStarts";
import { Button } from "@/ui/button";
import { formatNumberInK } from "@/lib/utils";

const GitHubStarListItem: React.FC = () => {
  const { data } = useGitHubStarts({});

  const starCount = useMemo(() => {
    const count = data?.stargazers_count;
    return isNumber(count) ? formatNumberInK(count) : "9.6k";
  }, [data?.stargazers_count]);

  return (
    <li>
      <Button
        variant="outline"
        size="sm"
        className="ml-0.5 h-8 gap-1.5 px-2 dark:bg-primary-foreground"
        asChild
      >
        <a
          href="https://github.com/comet-ml/opik"
          target="_blank"
          rel="noreferrer"
        >
          <GitHubIcon className="size-3.5" />
          <span className="comet-body-s">Star</span>
          <span className="rounded-full bg-muted px-1.5 py-0.5 text-xs dark:bg-secondary">
            {starCount}
          </span>
        </a>
      </Button>
    </li>
  );
};

export default GitHubStarListItem;
