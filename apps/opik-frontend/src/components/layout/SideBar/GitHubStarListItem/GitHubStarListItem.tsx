import React, { useMemo } from "react";
import isNumber from "lodash/isNumber";
import GitHubIcon from "@/icons/github.svg?react";

import useGitHubStarts from "@/api/external/useGitHubStarts";
import { Button } from "@/components/ui/button";
import { cn, formatNumberInK } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

export interface GitHubStarListItemProps {
  expanded: boolean;
}

const GitHubStarListItem: React.FC<GitHubStarListItemProps> = ({
  expanded,
}) => {
  const { data } = useGitHubStarts({});

  const starCount = useMemo(() => {
    const count = data?.stargazers_count;
    return isNumber(count) ? formatNumberInK(count) : "9.6k";
  }, [data?.stargazers_count]);

  const itemElement = (
    <li className="mb-2">
      <Button
        variant="outline"
        size="sm"
        className={cn(
          expanded ? "ml-0.5 h-8 gap-1.5 px-2" : "size-8 p-0 max-w-full",
          "dark:bg-primary-foreground",
        )}
        asChild
      >
        <a
          href="https://github.com/comet-ml/opik"
          target="_blank"
          rel="noreferrer"
        >
          <GitHubIcon className="size-3.5" />
          {expanded && (
            <>
              <span className="comet-body-s">Star</span>
              <span className="rounded-full bg-muted px-1.5 py-0.5 text-xs dark:bg-secondary">
                {starCount}
              </span>
            </>
          )}
        </a>
      </Button>
    </li>
  );

  if (expanded) {
    return itemElement;
  }

  return (
    <TooltipWrapper content={`GitHub star ${starCount}`} side="right">
      {itemElement}
    </TooltipWrapper>
  );
};

export default GitHubStarListItem;
