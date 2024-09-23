import { useMemo } from "react";
import { tags as t } from "@lezer/highlight";
import { githubLightInit } from "@uiw/codemirror-theme-github";

export const useCodemirrorTheme = () => {
  return useMemo(
    () =>
      githubLightInit({
        settings: {
          fontFamily: `Ubuntu Mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace`,
          foreground: "#030712",
          background: "#F8FAFC",
          gutterBackground: "#F8FAFC",
          gutterForeground: "#94A3B8",
          gutterBorder: "#F8FAFC",
        },
        styles: [{ tag: [t.className, t.propertyName], color: "#005CC5" }],
      }),
    [],
  );
};
