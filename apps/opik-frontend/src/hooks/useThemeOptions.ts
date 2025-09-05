import { useCallback } from "react";
import { Monitor, Moon, Sun } from "lucide-react";

import { useTheme } from "@/components/theme-provider";
import { DropdownOption } from "@/types/shared";
import { SYSTEM_THEME_MODE, THEME_MODE, ThemeMode } from "@/constants/theme";

export type ThemeOption = DropdownOption<ThemeMode> & {
  icon: React.ComponentType<{ className?: string }>;
};

export const THEME_OPTIONS: ThemeOption[] = [
  { value: THEME_MODE.LIGHT, label: "Light", icon: Sun },
  { value: THEME_MODE.DARK, label: "Dark", icon: Moon },
  { value: SYSTEM_THEME_MODE.SYSTEM, label: "System", icon: Monitor },
];

export const useThemeOptions = () => {
  const { theme, setTheme } = useTheme();

  const handleThemeSelect = useCallback(
    (selectedTheme: ThemeMode) => {
      setTheme(selectedTheme);
    },
    [setTheme],
  );

  const currentOption = THEME_OPTIONS.find((option) => option.value === theme);
  const CurrentIcon = currentOption?.icon || Sun;

  return {
    theme,
    themeOptions: THEME_OPTIONS,
    currentOption,
    CurrentIcon,
    handleThemeSelect,
  };
};
