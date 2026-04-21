import { clients } from "@/client/Client";
import { logger } from "@/utils/logger";
import { getTrackOpikClient } from "@/decorators/track";

export const flushAll = async () => {
  logger.debug("Starting flushAll operation");
  try {
    await Promise.all([
      getTrackOpikClient().flush({ silent: true }),
      ...clients.map((c) => c.flush({ silent: true })),
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
