export type ThemeMode = "light" | "dark";
export type Theme = ThemeMode | "system";
export type ThemeVariant = "default" | "high-contrast" | "midnight";

export interface ThemePreferences {
  mode: Theme;
  variant: ThemeVariant;
}

export interface ThemeProviderState {
  themeMode: ThemeMode;
  theme: Theme;
  variant: ThemeVariant;
  setTheme: (theme: Theme) => void;
  setVariant: (variant: ThemeVariant) => void;
  preferences: ThemePreferences;
  setPreferences: (preferences: Partial<ThemePreferences>) => void;
}

export const DEFAULT_THEME_PREFERENCES: ThemePreferences = {
  mode: "system",
  variant: "default",
};

export const THEME_STORAGE_KEY = "opik-theme-preferences";
