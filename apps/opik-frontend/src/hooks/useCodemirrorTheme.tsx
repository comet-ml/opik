import { useMemo } from "react";
import { tags as t } from "@lezer/highlight";
import { githubLightInit } from "@uiw/codemirror-theme-github";

type CodemirrorThemeProps = {
  editable?: boolean;
};

export const useCodemirrorTheme = (props?: CodemirrorThemeProps) => {
  const { editable = false } = props || {};
  return useMemo(
    () =>
      githubLightInit({
        settings: {
          fontFamily: `Ubuntu Mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace`,
          fontSize: "0.875rem",
          foreground: "#030712",
          background: "#F8FAFC",
          gutterBackground: "#F8FAFC",
          gutterForeground: "#94A3B8",
          gutterBorder: "#F8FAFC",
          lineHighlight: editable ? "#F1F5F9" : "transparent",
        },
        styles: [{ tag: [t.className, t.propertyName], color: "#005CC5" }],
      }),
    [editable],
  );
};
