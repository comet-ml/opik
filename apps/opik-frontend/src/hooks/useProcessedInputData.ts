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

    setIsDetecting(true);

    detectAdditionalMedia(input, initialMedia)
      .then(setMedia)
      .finally(() => setIsDetecting(false));
  }, [input, initialMedia]);

  return { media, formattedData, isDetecting };
};
