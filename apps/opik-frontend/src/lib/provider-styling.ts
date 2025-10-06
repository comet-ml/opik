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
): "default" | "secondary" | "outline" => {
  const variants: Record<PROVIDER_TYPE, "default" | "secondary" | "outline"> = {
    [PROVIDER_TYPE.OPEN_AI]: "default",
    [PROVIDER_TYPE.ANTHROPIC]: "secondary",
    [PROVIDER_TYPE.GEMINI]: "outline",
    [PROVIDER_TYPE.VERTEX_AI]: "outline",
    [PROVIDER_TYPE.OPEN_ROUTER]: "secondary",
    [PROVIDER_TYPE.CUSTOM]: "outline",
  };

  return variants[provider] || "outline";
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
