import React, { useMemo } from "react";
import isNumber from "lodash/isNumber";
import GitHubIcon from "@/icons/github.svg?react";

import useGitHubStarts from "@/api/external/useGitHubStarts";
import { Button } from "@/ui/button";
import { cn, formatNumberInK } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

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
    <li className="mb-2 pl-0.5">
      <Button
        variant="outline"
        size="sm"
        className={cn(
          expanded ? "h-6 gap-1 pl-1.5 pr-1" : "size-6 p-0 max-w-full",
          "dark:bg-primary-foreground",
        )}
        asChild
      >
        <a
          href="https://github.com/comet-ml/opik"
          target="_blank"
          rel="noreferrer"
        >
          <GitHubIcon className="size-3" />
          {expanded && (
            <>
              <span className="comet-body-xs-accented">Star</span>
              <span className="rounded bg-chart-gray-light px-1 text-[10px] font-medium leading-4 text-chart-gray-dark">
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
    <TooltipWrapper
      content={`GitHub star ${starCount}`}
      side="right"
      delayDuration={0}
    >
      {itemElement}
    </TooltipWrapper>
  );
};

export default GitHubStarListItem;
