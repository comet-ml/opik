export const createBatchProcessor = <T>(
  processCallback: (items: T[]) => void,
  maxBatchSize = 20,
  flushInterval = 2000,
) => {
  let currentBatch: T[] = [];
  let flushTimer: NodeJS.Timeout | null = null;

  const processCurrentBatch = () => {
    if (currentBatch.length > 0) {
      processCallback(currentBatch);
      currentBatch = [];
    }
  };

  const flushBatch = () => {
    processCurrentBatch();

    if (flushTimer) {
      clearTimeout(flushTimer);
      flushTimer = null;
    }
  };

  const startFlushTimer = () => {
    if (flushTimer) {
      clearTimeout(flushTimer);
    }

    flushTimer = setTimeout(() => {
      flushBatch();
    }, flushInterval);
  };

  const addItemToBatch = (item: T) => {
    currentBatch.push(item);

    if (currentBatch.length >= maxBatchSize) {
      flushBatch();
    } else {
      startFlushTimer();
    }
  };

  return {
    addItem: addItemToBatch,
    flush: flushBatch,
  };
};
