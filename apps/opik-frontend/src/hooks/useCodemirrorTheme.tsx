import { useMemo } from "react";
import { tags as t } from "@lezer/highlight";
import { githubDarkInit, githubLightInit } from "@uiw/codemirror-theme-github";
import { useTheme } from "@/components/theme-provider";
import { THEME_MODE } from "@/constants/theme";

type CodemirrorThemeProps = {
  editable?: boolean;
};

export const useCodemirrorTheme = (props?: CodemirrorThemeProps) => {
  const { editable = false } = props || {};
  const { themeMode } = useTheme();
  const isDark = themeMode === THEME_MODE.DARK;

  return useMemo(() => {
    const themeInit = isDark ? githubDarkInit : githubLightInit;
    return themeInit({
      settings: {
        fontFamily: `Ubuntu Mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace`,
        fontSize: "0.875rem",
        foreground: "hsl(var(--text-primary))",
        background: "var(--codemirror-background)",
        gutterBackground: "var(--codemirror-background)",
        gutterForeground: "var(--codemirror-gutter)",
        gutterBorder: "var(--codemirror-background)",
        lineHighlight: editable
          ? "var(--codemirror-line-highlight)"
          : "transparent",
      },
      styles: [
        {
          tag: [t.className, t.propertyName],
          color: "var(--codemirror-syntax-blue)",
        },
      ],
    });
  }, [editable, isDark]);
};
