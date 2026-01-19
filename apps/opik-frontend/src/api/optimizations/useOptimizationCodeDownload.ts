import { useCallback } from "react";
import FileSaver from "file-saver";
import { useToast } from "@/components/ui/use-toast";
import api from "@/api/api";
import { OptimizationStudioConfig } from "@/types/optimizations";
import { OPTIMIZATIONS_REST_ENDPOINT } from "@/api/api";

/**
 * Sanitizes a filename to be safe for file systems.
 * Converts to lowercase, replaces spaces with underscores, and removes unsafe characters.
 *
 * @param name - The name to sanitize
 * @returns A safe filename
 */
function sanitizeFilename(name: string | undefined | null): string {
  if (!name || name.trim().length === 0) {
    return "optimization";
  }

  // Convert to lowercase
  let sanitized = name.toLowerCase().trim();

  // Replace spaces with underscores
  sanitized = sanitized.replace(/\s+/g, "_");

  // Remove unsafe characters: / \ : * ? " < > |
  sanitized = sanitized.replace(/[/\\:*?"<>|]/g, "");

  // Remove leading/trailing dots and spaces
  sanitized = sanitized.replace(/^\.+|\.+$/g, "").trim();

  // Ensure it's not empty after sanitization
  if (sanitized.length === 0) {
    return "optimization";
  }

  return sanitized;
}

/**
 * Hook to download optimization code as a Python file.
 *
 * @returns Function to trigger code download
 */
export default function useOptimizationCodeDownload() {
  const { toast } = useToast();

  const downloadCode = useCallback(
    async (
      config: OptimizationStudioConfig,
      optimizationName?: string | null,
    ) => {
      try {
        const response = await api.post<string>(
          `${OPTIMIZATIONS_REST_ENDPOINT}studio/code`,
          config,
          {
            responseType: "text",
          },
        );

        // Sanitize the optimization name for the filename
        const sanitizedName = sanitizeFilename(optimizationName);
        const filename = `${sanitizedName}.py`;

        // Create blob and download
        const blob = new Blob([response.data], {
          type: "text/plain;charset=utf-8",
        });
        FileSaver.saveAs(blob, filename);
      } catch (error: unknown) {
        const errorMessage =
          (error as { response?: { data?: { message?: string } } })?.response
            ?.data?.message ||
          (error as { message?: string })?.message ||
          "Failed to generate optimization code";

        toast({
          title: "Download failed",
          description: errorMessage,
          variant: "destructive",
        });
        throw error;
      }
    },
    [toast],
  );

  return { downloadCode };
}
