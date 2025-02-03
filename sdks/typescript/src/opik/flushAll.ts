import { clients } from "@/client/Client";
import { trackOpikClient } from "@/decorators/track";

export const flushAll = async () => {
  await Promise.all([
    trackOpikClient.flush(),
    ...clients.map((c) => c.flush()),
  ]);
};
