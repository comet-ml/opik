import { useEffect, useMemo, useState } from "react";
import { ParsedMediaData } from "@/types/attachments";
import { detectAdditionalMedia } from "@/lib/media";
import { processInputData } from "@/lib/images";

export type UseProcessedInputDataReturn = {
  media: ParsedMediaData[];
  formattedData: object | undefined;
  isDetecting: boolean;
};

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
