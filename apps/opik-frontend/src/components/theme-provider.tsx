import { createContext, useContext, useEffect, useMemo, useState } from "react";
import {
  SYSTEM_THEME_MODE,
  THEME_MODE,
  type ThemeMode,
} from "@/constants/theme";

type ThemeProviderProps = {
  children: React.ReactNode;
  defaultTheme?: ThemeMode;
  storageKey?: string;
};

type ThemeProviderState = {
  themeMode: THEME_MODE;
  theme: ThemeMode;
  setTheme: (theme: ThemeMode) => void;
};

const initialState: ThemeProviderState = {
  theme: SYSTEM_THEME_MODE.SYSTEM,
  themeMode: THEME_MODE.LIGHT,
  setTheme: () => null,
};

const calculateThemeMode = (theme: ThemeMode) => {
  if (theme === SYSTEM_THEME_MODE.SYSTEM) {
    return window.matchMedia("(prefers-color-scheme: dark)").matches
      ? THEME_MODE.DARK
      : THEME_MODE.LIGHT;
  }
  return theme;
};

const ThemeProviderContext = createContext<ThemeProviderState>(initialState);

export function ThemeProvider({
  children,
  defaultTheme = THEME_MODE.LIGHT,
  storageKey = "vite-ui-theme",
  ...props
}: ThemeProviderProps) {
  const [theme, setTheme] = useState<ThemeMode>(
    () => (localStorage.getItem(storageKey) as ThemeMode) || defaultTheme,
  );

  useEffect(() => {
    const root = window.document.documentElement;
    root.classList.remove(THEME_MODE.LIGHT, THEME_MODE.DARK);
    root.classList.add(calculateThemeMode(theme));
  }, [theme]);

  const value = useMemo(
    () => ({
      themeMode: calculateThemeMode(theme),
      theme,
      setTheme: (newTheme: ThemeMode) => {
        localStorage.setItem(storageKey, newTheme);
        setTheme(newTheme);
      },
    }),
    [theme, storageKey],
  );

  return (
    <ThemeProviderContext.Provider {...props} value={value}>
      {children}
    </ThemeProviderContext.Provider>
  );
}

export const useTheme = () => {
  const context = useContext(ThemeProviderContext);

  if (context === undefined)
    throw new Error("useTheme must be used within a ThemeProvider");

  return context;
};
