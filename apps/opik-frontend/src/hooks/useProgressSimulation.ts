import { useState, useEffect, useCallback } from "react";

interface UseProgressSimulationOptions {
  /**
   * Array of messages to cycle through during progress simulation
   */
  messages: string[];
  /**
   * Whether the progress simulation is active
   */
  isPending: boolean;
  /**
   * Interval in milliseconds for progress updates (default: 800ms)
   */
  intervalMs?: number;
  /**
   * Whether to loop the progress animation continuously (default: false)
   */
  loop?: boolean;
}

interface UseProgressSimulationReturn {
  /**
   * Current progress percentage (0-100)
   */
  progress: number;
  /**
   * Current progress message
   */
  message: string;
  /**
   * Function to mark progress as complete
   */
  complete: () => void;
}

/**
 * Hook to simulate progress with percentage and rotating messages
 * @param options - Configuration options
 * @returns Progress state and completion callback
 */
const useProgressSimulation = ({
  messages,
  isPending,
  intervalMs = 800,
  loop = false,
}: UseProgressSimulationOptions): UseProgressSimulationReturn => {
  const [progress, setProgress] = useState(0);
  const [message, setMessage] = useState("");

  useEffect(() => {
    if (!isPending) {
      setProgress(0);
      setMessage("");
      return;
    }

    setProgress(0);
    setMessage(messages[0] || "");

    const interval = setInterval(() => {
      setProgress((prev) => {
        const next = prev + Math.random() * 15;

        if (loop) {
          // In loop mode, reset to 0 when reaching 100
          if (next >= 100) {
            setMessage(messages[0] || "");
            return 0;
          }
        } else {
          // In normal mode, cap at 90
          if (next > 90) return 90;
        }

        // Calculate which message to show based on progress
        const maxProgress = loop ? 100 : 90;
        const messageIndex = Math.min(
          Math.floor((next / maxProgress) * messages.length),
          messages.length - 1,
        );
        setMessage(messages[messageIndex] || "");

        return next;
      });
    }, intervalMs);

    return () => clearInterval(interval);
  }, [isPending, messages, intervalMs, loop]);

  const complete = useCallback(() => {
    setProgress(100);
    setMessage(messages[messages.length - 1] || "Completed successfully!");
  }, [messages]);

  return { progress, message, complete };
};

export default useProgressSimulation;
