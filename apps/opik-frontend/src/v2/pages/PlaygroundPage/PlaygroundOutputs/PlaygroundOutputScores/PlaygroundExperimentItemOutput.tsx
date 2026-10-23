import React from "react";

import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";
import { PlaygroundExperimentItem } from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/usePlaygroundExperimentItem";

interface PlaygroundExperimentItemOutputProps {
  item: PlaygroundExperimentItem;
}

const PlaygroundExperimentItemOutput: React.FunctionComponent<
  PlaygroundExperimentItemOutputProps
> = ({ item }) => {
  if (item.output === null) {
    return null;
  }

  return (
    <div className="flex flex-col gap-1">
      {item.runCount > 1 && (
        <span className="comet-body-s text-muted-gray">Output (last run):</span>
      )}
      <MarkdownPreview>{item.output}</MarkdownPreview>
    </div>
  );
};

export default PlaygroundExperimentItemOutput;
