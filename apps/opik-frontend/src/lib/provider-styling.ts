import { PROVIDER_TYPE } from "@/types/providers";
import { cn } from "@/lib/utils";

/**
 * Provider-specific styling utilities
 */
export const getProviderColor = (provider: PROVIDER_TYPE): string => {
  const colors: Record<PROVIDER_TYPE, string> = {
    [PROVIDER_TYPE.OPEN_AI]: "text-green-600",
    [PROVIDER_TYPE.ANTHROPIC]: "text-orange-600",
    [PROVIDER_TYPE.GEMINI]: "text-blue-600",
    [PROVIDER_TYPE.VERTEX_AI]: "text-purple-600",
    [PROVIDER_TYPE.OPEN_ROUTER]: "text-indigo-600",
    [PROVIDER_TYPE.CUSTOM]: "text-gray-600",
  };

  return colors[provider] || "text-gray-600";
};

export const getProviderBadgeVariant = (
  provider: PROVIDER_TYPE,
): "default" | "primary" | "gray" | "purple" | "burgundy" | "pink" | "red" | "orange" | "yellow" | "green" | "turquoise" | "blue" => {
  const variants: Record<PROVIDER_TYPE, "default" | "primary" | "gray" | "purple" | "burgundy" | "pink" | "red" | "orange" | "yellow" | "green" | "turquoise" | "blue"> = {
    [PROVIDER_TYPE.OPEN_AI]: "green",
    [PROVIDER_TYPE.ANTHROPIC]: "orange",
    [PROVIDER_TYPE.GEMINI]: "blue",
    [PROVIDER_TYPE.VERTEX_AI]: "purple",
    [PROVIDER_TYPE.OPEN_ROUTER]: "gray",
    [PROVIDER_TYPE.CUSTOM]: "gray",
  };

  return variants[provider] || "gray";
};

export const getProviderIcon = (provider: PROVIDER_TYPE): string => {
  const icons: Record<PROVIDER_TYPE, string> = {
    [PROVIDER_TYPE.OPEN_AI]: "🤖",
    [PROVIDER_TYPE.ANTHROPIC]: "🧠",
    [PROVIDER_TYPE.GEMINI]: "💎",
    [PROVIDER_TYPE.VERTEX_AI]: "☁️",
    [PROVIDER_TYPE.OPEN_ROUTER]: "🔗",
    [PROVIDER_TYPE.CUSTOM]: "⚙️",
  };

  return icons[provider] || "❓";
};

export const getProviderClassName = (
  provider: PROVIDER_TYPE,
  baseClassName?: string,
): string => {
  return cn(baseClassName, getProviderColor(provider), "font-medium");
};
