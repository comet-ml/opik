/**
 * Estimates the size of a payload in bytes by serializing it to JSON
 */
const estimatePayloadSize = <T>(items: T[]): number => {
  try {
    return new Blob([JSON.stringify(items)]).size;
  } catch {
    // Fallback: rough estimate based on item count
    return items.length * 1000;
  }
};

/**
 * Checks if an item contains images by looking for base64 data or image URLs
 */
const hasImages = (item: unknown): boolean => {
  try {
    const itemString = JSON.stringify(item);
    // Check for base64 images (data:image/...;base64,)
    if (/data:image\/[^;]+;base64,/.test(itemString)) {
      return true;
    }
    // Check for image URLs
    if (/https?:\/\/[^\s"]+\.(jpg|jpeg|png|gif|webp|bmp|svg)/i.test(itemString)) {
      return true;
    }
    // Check for image tags
    if (/<<<image>>>/.test(itemString)) {
      return true;
    }
    return false;
  } catch {
    return false;
  }
};

export const createBatchProcessor = <T>(
  processCallback: (items: T[]) => void,
  maxBatchSize = 20,
  flushInterval = 2000,
  maxPayloadSizeBytes?: number,
) => {
  let currentBatch: T[] = [];
  let flushTimer: NodeJS.Timeout | null = null;

  // Default max payload size: 2MB (conservative to avoid timeouts)
  const defaultMaxPayloadSize = maxPayloadSizeBytes ?? 2 * 1024 * 1024;

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

  const shouldFlush = (newItem: T): boolean => {
    // Always flush if we've reached max batch size
    if (currentBatch.length >= maxBatchSize) {
      return true;
    }

    // Check payload size if maxPayloadSize is set
    if (maxPayloadSizeBytes !== undefined) {
      const testBatch = [...currentBatch, newItem];
      const estimatedSize = estimatePayloadSize(testBatch);
      if (estimatedSize >= defaultMaxPayloadSize) {
        return true;
      }
    }

    // If the new item has images, be more conservative with batch size
    if (hasImages(newItem)) {
      // Reduce effective batch size for items with images
      const imageBatchSize = Math.max(1, Math.floor(maxBatchSize / 2));
      if (currentBatch.length >= imageBatchSize) {
        return true;
      }
    }

    return false;
  };

  const addItemToBatch = (item: T) => {
    // Check if we should flush before adding the new item
    if (shouldFlush(item)) {
      flushBatch();
    }

    currentBatch.push(item);

    // Check payload size after adding (in case the item itself is very large)
    if (
      maxPayloadSizeBytes !== undefined &&
      estimatePayloadSize(currentBatch) >= defaultMaxPayloadSize
    ) {
      flushBatch();
      return;
    }

    // Start timer for next flush, or flush immediately if batch is full
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
