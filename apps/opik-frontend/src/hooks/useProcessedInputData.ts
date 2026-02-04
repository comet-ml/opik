import { useEffect, useMemo, useState } from "react";
import { ParsedMediaData } from "@/types/attachments";
import { detectAdditionalMedia } from "@/lib/media";
import { processInputData } from "@/lib/images";

export type UseProcessedInputDataReturn = {
  media: ParsedMediaData[];
  formattedData: object | undefined;
  isDetecting: boolean;
};

/**
 * Hook that processes input data to extract media with async detection.
 * This hook wraps processInputData with async media detection capabilities,
 * allowing detection of extension-less URLs via Content-Type headers.
 *
 * @param input - The input data object to process
 * @returns Object containing media array, formatted data, and detection state
 *
 * @example
 * ```tsx
 * const { media, formattedData, isDetecting } = useProcessedInputData(data);
 * ```
 */
export const useProcessedInputData = (
  input: object | undefined,
): UseProcessedInputDataReturn => {
  const { media: initialMedia, formattedData } = useMemo(
    () => processInputData(input),
    [input],
  );

  const [media, setMedia] = useState<ParsedMediaData[]>(initialMedia);
  const [isDetecting, setIsDetecting] = useState(false);

  useEffect(() => {
    // Reset to initial media when input changes
    setMedia(initialMedia);

    if (!input) {
      return;
    }

    let isCancelled = false;
    setIsDetecting(true);

    detectAdditionalMedia(input, initialMedia)
      .then((result) => {
        if (!isCancelled) {
          setMedia(result);
        }
      })
      .catch((error) => {
        // Silently fail - async detection is optional enhancement
        console.warn("Async media detection failed:", error);
      })
      .finally(() => {
        if (!isCancelled) {
          setIsDetecting(false);
        }
      });

    return () => {
      isCancelled = true;
    };
  }, [input, initialMedia]);

  return { media, formattedData, isDetecting };
};
