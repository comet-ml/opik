import React from "react";
import { useTheme } from "@/components/theme/ThemeProvider";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Switch } from "@/components/ui/switch";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { ThemePreview } from "./ThemePreview";
import { Moon, Sun, Monitor, Palette, Clock, Contrast } from "lucide-react";
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
  const {
    theme,
    themeMode,
    variant,
    preferences,
    setTheme,
    setVariant,
    setPreferences,
  } = useTheme();

  // Keyboard shortcuts
  useHotkeys(
    "cmd+shift+d, ctrl+shift+d",
    () => {
      const newTheme = themeMode === "dark" ? "light" : "dark";
      setTheme(newTheme);
    },
    [themeMode, setTheme],
  );

  const handleTimeChange = (type: "day" | "night", value: string) => {
    setPreferences({
      switchTime: {
        day: preferences.switchTime?.day || "08:00",
        night: preferences.switchTime?.night || "20:00",
        [type]: value,
      },
    });
  };

  return (
    <div className="grid gap-6 md:grid-cols-2">
      <div className="space-y-6">
        {/* Theme Selection */}
        <Card>
          <CardHeader>
            <CardTitle>Theme Mode</CardTitle>
            <CardDescription>
              Choose your preferred color scheme. Press{" "}
              <kbd className="rounded border px-1 py-0.5 text-xs">
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
                    <RadioGroupItem value={option.value} id={option.value} />
                    <div className="flex-1 space-y-1">
                      <div className="flex items-center gap-2">
                        {option.icon}
                        <span className="font-medium">{option.label}</span>
                      </div>
                      <p className="text-sm text-muted-foreground">
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
                      />
                      <div className="flex-1 space-y-1">
                        <div className="flex items-center gap-2">
                          {option.icon}
                          <span className="font-medium">{option.label}</span>
                        </div>
                        <p className="text-sm text-muted-foreground">
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

        {/* Auto Switch Settings */}
        <Card>
          <CardHeader>
            <CardTitle>Auto Theme Switching</CardTitle>
            <CardDescription>
              Automatically switch between light and dark themes based on time
              of day
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Clock className="size-4" />
                <Label htmlFor="auto-switch">Enable auto-switching</Label>
              </div>
              <Switch
                id="auto-switch"
                checked={preferences.autoSwitch}
                onCheckedChange={(checked) =>
                  setPreferences({ autoSwitch: checked })
                }
              />
            </div>

            {preferences.autoSwitch && (
              <div className="space-y-4 pl-6">
                <div className="space-y-2">
                  <Label htmlFor="day-time">Light theme starts at</Label>
                  <Select
                    value={preferences.switchTime?.day}
                    onValueChange={(value) => handleTimeChange("day", value)}
                  >
                    <SelectTrigger id="day-time">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {Array.from({ length: 24 }, (_, i) => {
                        const hour = String(i).padStart(2, "0");
                        return (
                          <SelectItem key={hour} value={`${hour}:00`}>
                            {hour}:00
                          </SelectItem>
                        );
                      })}
                    </SelectContent>
                  </Select>
                </div>

                <div className="space-y-2">
                  <Label htmlFor="night-time">Dark theme starts at</Label>
                  <Select
                    value={preferences.switchTime?.night}
                    onValueChange={(value) => handleTimeChange("night", value)}
                  >
                    <SelectTrigger id="night-time">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {Array.from({ length: 24 }, (_, i) => {
                        const hour = String(i).padStart(2, "0");
                        return (
                          <SelectItem key={hour} value={`${hour}:00`}>
                            {hour}:00
                          </SelectItem>
                        );
                      })}
                    </SelectContent>
                  </Select>
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Theme Preview */}
      <div className="sticky top-4">
        <ThemePreview />
      </div>
    </div>
  );
};
