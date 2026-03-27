export const ASSISTANT_DEV_BASE_URL =
  (import.meta.env.VITE_ASSISTANT_SIDEBAR_BASE_URL as string) ?? "";

export const IS_ASSISTANT_DEV = ASSISTANT_DEV_BASE_URL !== "";
