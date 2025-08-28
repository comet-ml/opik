import {
  Theme,
  ThemeMode,
  ThemePreferences,
  ThemeVariant,
  DEFAULT_THEME_PREFERENCES,
  THEME_STORAGE_KEY,
} from "./types";

export const calculateThemeMode = (theme: Theme): ThemeMode => {
  if (theme === "system") {
    return window.matchMedia("(prefers-color-scheme: dark)").matches
      ? "dark"
      : "light";
  }
  return theme;
};

export const getStoredThemePreferences = (): ThemePreferences => {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    if (stored) {
      const parsed = JSON.parse(stored);
      return {
        ...DEFAULT_THEME_PREFERENCES,
        ...parsed,
      };
    }
  } catch (error) {
    console.error("Failed to parse theme preferences:", error);
  }
  return DEFAULT_THEME_PREFERENCES;
};

export const storeThemePreferences = (preferences: ThemePreferences): void => {
  try {
    localStorage.setItem(THEME_STORAGE_KEY, JSON.stringify(preferences));
  } catch (error) {
    console.error("Failed to store theme preferences:", error);
  }
};

export const applyThemeToDocument = (
  themeMode: ThemeMode,
  variant: ThemeVariant,
): void => {
  const root = window.document.documentElement;

  // Remove all theme classes
  root.classList.remove("light", "dark");
  root.classList.remove(
    "theme-default",
    "theme-high-contrast",
    "theme-midnight",
  );

  // Apply theme mode
  root.classList.add(themeMode);

  // Apply variant class only for dark mode
  if (themeMode === "dark") {
    root.classList.add(`theme-${variant}`);
  }
};
