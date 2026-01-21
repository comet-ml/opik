import { useCallback, useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import { usePromptMap } from "@/store/PlaygroundStore";
import { Span, Trace } from "@/types/traces";
import {
  extractPlaygroundData,
  PlaygroundPrefillData,
} from "@/lib/playground/extractPlaygroundData";

// Key used to store prefill data in localStorage
export const PLAYGROUND_PREFILL_KEY = "opik-playground-prefill";

/**
 * Hook to open trace/span data in the playground
 * Stores the extracted data in localStorage and navigates to playground
 */
const useOpenInPlayground = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const promptMap = usePromptMap();

  // Check if playground is empty (same logic as useLoadPlayground)
  const isPlaygroundEmpty = useMemo(() => {
    const keys = Object.keys(promptMap);

    return (
      keys.length === 1 &&
      promptMap[keys[0]]?.messages?.length === 1 &&
      promptMap[keys[0]]?.messages[0]?.content === ""
    );
  }, [promptMap]);

  const openInPlayground = useCallback(
    (data: Trace | Span, allData?: Array<Trace | Span>) => {
      // Extract playground-compatible data
      const prefillData = extractPlaygroundData(data, allData);

      // Store in localStorage for the playground to pick up
      localStorage.setItem(PLAYGROUND_PREFILL_KEY, JSON.stringify(prefillData));

      // Navigate to playground
      navigate({
        to: "/$workspaceName/playground",
        params: { workspaceName },
      });
    },
    [navigate, workspaceName],
  );

  return { openInPlayground, isPlaygroundEmpty };
};

/**
 * Get and clear prefill data from localStorage
 * Returns null if no data exists or parsing fails
 */
export const getAndClearPlaygroundPrefill =
  (): PlaygroundPrefillData | null => {
    try {
      const data = localStorage.getItem(PLAYGROUND_PREFILL_KEY);
      if (!data) return null;

      // Clear the data after reading
      localStorage.removeItem(PLAYGROUND_PREFILL_KEY);

      return JSON.parse(data) as PlaygroundPrefillData;
    } catch {
      localStorage.removeItem(PLAYGROUND_PREFILL_KEY);
      return null;
    }
  };

export default useOpenInPlayground;
