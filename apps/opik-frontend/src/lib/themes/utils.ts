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

export const shouldUseDarkMode = (preferences: ThemePreferences): boolean => {
  if (!preferences.autoSwitch) {
    return calculateThemeMode(preferences.mode) === "dark";
  }

  // Auto-switch based on time
  const now = new Date();
  const currentTime = now.getHours() * 60 + now.getMinutes();

  const [dayHours, dayMinutes] = (preferences.switchTime?.day || "08:00")
    .split(":")
    .map(Number);
  const [nightHours, nightMinutes] = (preferences.switchTime?.night || "20:00")
    .split(":")
    .map(Number);

  const dayTime = dayHours * 60 + dayMinutes;
  const nightTime = nightHours * 60 + nightMinutes;

  return currentTime < dayTime || currentTime >= nightTime;
};
