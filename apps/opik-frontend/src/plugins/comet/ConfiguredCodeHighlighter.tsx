import React from "react";
import useUser from "./useUser";
import ConfiguredCodeHighlighterCore, {
  ConfiguredCodeHighlighterCoreProps,
} from "@/components/pages-shared/onboarding/CreateExperimentCode/ConfiguredCodeHighlighterCore";

const ConfiguredCodeHighlighter: React.FC<
  ConfiguredCodeHighlighterCoreProps
> = (props) => {
  const { data: user } = useUser();

  if (!user) return;

  return <ConfiguredCodeHighlighterCore {...props} apiKey={user.apiKeys[0]} />;
};

export default ConfiguredCodeHighlighter;
