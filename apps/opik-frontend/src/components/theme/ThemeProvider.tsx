import React, {
  createContext,
  useContext,
  useEffect,
  useState,
  useCallback,
} from "react";
import {
  Theme,
  ThemeMode,
  ThemeVariant,
  ThemePreferences,
  ThemeProviderState,
  DEFAULT_THEME_PREFERENCES,
} from "@/lib/themes/types";
import {
  calculateThemeMode,
  getStoredThemePreferences,
  storeThemePreferences,
  applyThemeToDocument,
} from "@/lib/themes/utils";

type ThemeProviderProps = {
  children: React.ReactNode;
  defaultTheme?: Theme;
  defaultVariant?: ThemeVariant;
};

const initialState: ThemeProviderState = {
  theme: "system",
  themeMode: "light",
  variant: "default",
  preferences: DEFAULT_THEME_PREFERENCES,
  setTheme: () => null,
  setVariant: () => null,
  setPreferences: () => null,
};

const ThemeProviderContext = createContext<ThemeProviderState | undefined>(undefined);

export function ThemeProvider({
  children,
  defaultTheme = "system",
  defaultVariant = "default",
}: ThemeProviderProps) {
  const [preferences, setPreferencesState] = useState<ThemePreferences>(() => {
    const stored = getStoredThemePreferences();
    // Only use stored preferences if they exist, otherwise use defaults
    if (stored.mode === DEFAULT_THEME_PREFERENCES.mode && stored.variant === DEFAULT_THEME_PREFERENCES.variant) {
      return {
        mode: defaultTheme,
        variant: defaultVariant,
      };
    }
    return stored;
  });

  const [themeMode, setThemeMode] = useState<ThemeMode>(() => {
    return calculateThemeMode(preferences.mode);
  });

  // Update theme mode when preferences change
  useEffect(() => {
    const newThemeMode = calculateThemeMode(preferences.mode);
    setThemeMode(newThemeMode);
  }, [preferences]);

  // Apply theme to document
  useEffect(() => {
    applyThemeToDocument(themeMode, preferences.variant);
  }, [themeMode, preferences.variant]);

  // Listen for system theme changes
  useEffect(() => {
    if (preferences.mode !== "system") return;

    const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
    const handleChange = () => {
      if (preferences.mode === "system") {
        setThemeMode(mediaQuery.matches ? "dark" : "light");
      }
    };

    // Check if addEventListener is supported, fallback to addListener for older browsers
    if (mediaQuery.addEventListener) {
      mediaQuery.addEventListener("change", handleChange);
      return () => mediaQuery.removeEventListener("change", handleChange);
    } else {
      mediaQuery.addListener(handleChange);
      return () => mediaQuery.removeListener(handleChange);
    }
  }, [preferences.mode]);

  const setTheme = useCallback(
    (theme: Theme) => {
      const newPreferences = { ...preferences, mode: theme };
      setPreferencesState(newPreferences);
      storeThemePreferences(newPreferences);
    },
    [preferences],
  );

  const setVariant = useCallback(
    (variant: ThemeVariant) => {
      const newPreferences = { ...preferences, variant };
      setPreferencesState(newPreferences);
      storeThemePreferences(newPreferences);
    },
    [preferences],
  );

  const setPreferences = useCallback(
    (updates: Partial<ThemePreferences>) => {
      const newPreferences = { ...preferences, ...updates };
      setPreferencesState(newPreferences);
      storeThemePreferences(newPreferences);
    },
    [preferences],
  );

  const value: ThemeProviderState = {
    themeMode,
    theme: preferences.mode,
    variant: preferences.variant,
    preferences,
    setTheme,
    setVariant,
    setPreferences,
  };

  return (
    <ThemeProviderContext.Provider value={value}>
      {children}
    </ThemeProviderContext.Provider>
  );
}

export const useTheme = () => {
  const context = useContext(ThemeProviderContext);

  if (context === undefined) {
    throw new Error("useTheme must be used within a ThemeProvider");
  }

  return context;
};
