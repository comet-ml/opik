export const THEME_MODE = {
  LIGHT: "light",
  DARK: "dark",
} as const;

export type ThemeMode = (typeof THEME_MODE)[keyof typeof THEME_MODE];
