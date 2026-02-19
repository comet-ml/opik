import React from "react";
import PlaygroundExperimentsLinkComponent, {
  PlaygroundExperimentsLinkProps,
} from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputActions/PlaygroundExperimentsLink";
import useUserPermission from "./useUserPermission";

const PlaygroundExperimentsLink: React.FC<PlaygroundExperimentsLinkProps> = (
  props,
) => {
  const { canViewExperiments } = useUserPermission();

  return (
    <PlaygroundExperimentsLinkComponent
      {...props}
      canViewExperiments={!!canViewExperiments}
    />
  );
};

export default PlaygroundExperimentsLink;
