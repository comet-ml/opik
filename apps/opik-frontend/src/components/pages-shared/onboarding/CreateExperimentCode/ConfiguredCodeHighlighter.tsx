import React from "react";
import usePluginsStore from "@/store/PluginsStore";
import ConfiguredCodeHighlighterCore, {
  ConfiguredCodeHighlighterCoreProps,
} from "./ConfiguredCodeHighlighterCore";

export type ConfiguredCodeHighlighterProps = Omit<
  ConfiguredCodeHighlighterCoreProps,
  "apiKey"
>;
const ConfiguredCodeHighlighter: React.FC<ConfiguredCodeHighlighterProps> = (
  props,
) => {
  const ConfiguredCodeHighlighter = usePluginsStore(
    (state) => state.ConfiguredCodeHighlighter,
  );

  if (ConfiguredCodeHighlighter) {
    return <ConfiguredCodeHighlighter {...props} />;
  }

  return <ConfiguredCodeHighlighterCore {...props} />;
};

export default ConfiguredCodeHighlighter;
