import React from "react";
import { useTheme } from "@/components/theme/ThemeProvider";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { ThemePreview } from "./ThemePreview";
import { Moon, Sun, Monitor, Palette, Contrast } from "lucide-react";
import { cn } from "@/lib/utils";
import { Theme, ThemeVariant } from "@/lib/themes/types";
import { useHotkeys } from "react-hotkeys-hook";

interface ThemeOption {
  value: Theme;
  label: string;
  icon: React.ReactNode;
  description: string;
}

interface VariantOption {
  value: ThemeVariant;
  label: string;
  icon: React.ReactNode;
  description: string;
}

const THEME_OPTIONS: ThemeOption[] = [
  {
    value: "light",
    label: "Light",
    icon: <Sun className="size-4" />,
    description: "Light theme with bright colors",
  },
  {
    value: "dark",
    label: "Dark",
    icon: <Moon className="size-4" />,
    description: "Dark theme for low-light environments",
  },
  {
    value: "system",
    label: "System",
    icon: <Monitor className="size-4" />,
    description: "Follow system preferences automatically",
  },
];

const VARIANT_OPTIONS: VariantOption[] = [
  {
    value: "default",
    label: "Default Dark",
    icon: <Palette className="size-4" />,
    description: "Balanced contrast with blue accents",
  },
  {
    value: "high-contrast",
    label: "High Contrast",
    icon: <Contrast className="size-4" />,
    description: "WCAG AAA compliant for better accessibility",
  },
  {
    value: "midnight",
    label: "Midnight",
    icon: <Moon className="size-4" />,
    description: "OLED-friendly with true blacks",
  },
];

export const AppearanceSettings: React.FC = () => {
  const { theme, themeMode, variant, setTheme, setVariant } = useTheme();

  // Keyboard shortcuts
  useHotkeys(
    "cmd+shift+d, ctrl+shift+d",
    () => {
      const newTheme = themeMode === "dark" ? "light" : "dark";
      setTheme(newTheme);
    },
    [themeMode, setTheme],
  );


  return (
    <div className="grid gap-6 md:grid-cols-2">
      <div className="space-y-6">
        {/* Theme Selection */}
        <Card>
          <CardHeader>
            <CardTitle>Theme Mode</CardTitle>
            <CardDescription>
              Choose your preferred color scheme. Press{" "}
              <kbd 
                className="rounded border px-1 py-0.5 text-xs"
                aria-label="Keyboard shortcut: Command plus Shift plus D"
              >
                Cmd+Shift+D
              </kbd>{" "}
              to toggle.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <RadioGroup
              value={theme}
              onValueChange={(value: string) => setTheme(value as Theme)}
            >
              {THEME_OPTIONS.map((option) => (
                <div key={option.value} className="mb-4">
                  <label
                    htmlFor={option.value}
                    className={cn(
                      "flex cursor-pointer items-center space-x-3 rounded-lg border p-4 hover:bg-accent",
                      theme === option.value && "border-primary bg-primary/5",
                    )}
                  >
                    <RadioGroupItem 
                      value={option.value} 
                      id={option.value}
                      aria-describedby={`${option.value}-description`}
                    />
                    <div className="flex-1 space-y-1">
                      <div className="flex items-center gap-2">
                        {option.icon}
                        <span className="font-medium">{option.label}</span>
                      </div>
                      <p className="text-sm text-muted-foreground" id={`${option.value}-description`}>
                        {option.description}
                      </p>
                    </div>
                  </label>
                </div>
              ))}
            </RadioGroup>
          </CardContent>
        </Card>

        {/* Dark Theme Variants */}
        {themeMode === "dark" && (
          <Card>
            <CardHeader>
              <CardTitle>Dark Theme Variant</CardTitle>
              <CardDescription>
                Fine-tune your dark mode experience with different variants
              </CardDescription>
            </CardHeader>
            <CardContent>
              <RadioGroup
                value={variant}
                onValueChange={(value: string) =>
                  setVariant(value as ThemeVariant)
                }
              >
                {VARIANT_OPTIONS.map((option) => (
                  <div key={option.value} className="mb-4">
                    <label
                      htmlFor={`variant-${option.value}`}
                      className={cn(
                        "flex cursor-pointer items-center space-x-3 rounded-lg border p-4 hover:bg-accent",
                        variant === option.value &&
                          "border-primary bg-primary/5",
                      )}
                    >
                      <RadioGroupItem
                        value={option.value}
                        id={`variant-${option.value}`}
                        aria-describedby={`variant-${option.value}-description`}
                      />
                      <div className="flex-1 space-y-1">
                        <div className="flex items-center gap-2">
                          {option.icon}
                          <span className="font-medium">{option.label}</span>
                        </div>
                        <p className="text-sm text-muted-foreground" id={`variant-${option.value}-description`}>
                          {option.description}
                        </p>
                      </div>
                    </label>
                  </div>
                ))}
              </RadioGroup>
            </CardContent>
          </Card>
        )}

      </div>

      {/* Theme Preview - Hidden but kept for debug */}
      <div className="sticky top-4 hidden">
        <ThemePreview />
      </div>
    </div>
  );
};
