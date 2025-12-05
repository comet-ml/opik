import { useEffect, useRef, useState } from "react";
import { DATASET_STATUS } from "@/types/datasets";

const AUTO_HIDE_DELAY_MS = 4000;

type UseDatasetLoadingStatusParams = {
  datasetStatus?: DATASET_STATUS;
};

type UseDatasetLoadingStatusReturn = {
  isProcessing: boolean;
  showSuccessMessage: boolean;
};

/**
 * Hook to track dataset loading status transitions.
 * Shows a success message when transitioning from processing to completed,
 * and auto-hides it after a delay.
 */
const useDatasetLoadingStatus = ({
  datasetStatus,
}: UseDatasetLoadingStatusParams): UseDatasetLoadingStatusReturn => {
  const previousStatusRef = useRef<DATASET_STATUS | undefined>(datasetStatus);
  const [showSuccessMessage, setShowSuccessMessage] = useState(false);

  const isProcessing = datasetStatus === DATASET_STATUS.processing;

  // Detect transition from processing to completed and show success message
  useEffect(() => {
    const wasProcessing =
      previousStatusRef.current === DATASET_STATUS.processing;
    const isNowCompleted = datasetStatus === DATASET_STATUS.completed;

    if (wasProcessing && isNowCompleted) {
      setShowSuccessMessage(true);

      // Auto-hide success message after delay
      const timeoutId = setTimeout(() => {
        setShowSuccessMessage(false);
      }, AUTO_HIDE_DELAY_MS);

      return () => clearTimeout(timeoutId);
    }

    previousStatusRef.current = datasetStatus;
  }, [datasetStatus]);

  return {
    isProcessing,
    showSuccessMessage,
  };
};

export default useDatasetLoadingStatus;
