import React from "react";

import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";
import { cn } from "@/lib/utils";
import { PlaygroundExperimentItem } from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/usePlaygroundExperimentItem";

interface PlaygroundExperimentItemOutputProps {
  item: PlaygroundExperimentItem;
  stale?: boolean;
}

const PlaygroundExperimentItemOutput: React.FunctionComponent<
  PlaygroundExperimentItemOutputProps
> = ({ item, stale }) => {
  if (item.output === null) {
    return null;
  }

  return (
    <div className="flex flex-col gap-1">
      {item.runCount > 1 && (
        <span className="comet-body-s text-muted-gray">Output (last run):</span>
      )}
      <MarkdownPreview className={cn(stale && "opacity-50")}>
        {item.output}
      </MarkdownPreview>
    </div>
  );
};

export default PlaygroundExperimentItemOutput;
