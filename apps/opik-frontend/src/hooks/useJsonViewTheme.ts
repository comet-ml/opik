import { useMemo } from "react";
import { useTheme } from "@/components/theme-provider";
import { THEME_MODE } from "@/constants/theme";

export type JsonViewThemeProps = {
  theme?:
    | "default"
    | "a11y"
    | "github"
    | "vscode"
    | "atom"
    | "winter-is-coming";
};

export const useJsonViewTheme = (props?: JsonViewThemeProps) => {
  const { theme: themeOverride } = props || {};
  const { themeMode } = useTheme();
  const isDark = themeMode === THEME_MODE.DARK;

  return useMemo(() => {
    const baseTheme = themeOverride || "github";

    return {
      theme: baseTheme,
      dark: isDark,
    };
  }, [themeOverride, isDark]);
};
