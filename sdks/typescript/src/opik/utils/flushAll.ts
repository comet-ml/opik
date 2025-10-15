import { clients } from "@/client/Client";
import { logger } from "@/utils/logger";
import { getTrackOpikClient } from "@/decorators/track";

export const flushAll = async () => {
  logger.debug("Starting flushAll operation");
  try {
    await Promise.all([
      getTrackOpikClient().flush(),
      ...clients.map((c) => c.flush()),
    ]);
    logger.debug("flushAll operation completed successfully");
  } catch (error) {
    logger.error("Error during flushAll operation:", {
      error: error instanceof Error ? error.message : error,
      stack: error instanceof Error ? error.stack : undefined,
    });
    throw error;
  }
};
