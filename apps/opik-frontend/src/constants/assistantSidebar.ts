export const ASSISTANT_SIDEBAR_DEFAULT_WIDTH = 400;
export const ASSISTANT_SIDEBAR_COLLAPSED_WIDTH = 33;

const WIDTH_STORAGE_KEY = "assistant-sidebar-width";
const OPEN_STORAGE_KEY = "assistant-sidebar-open";

export const getStoredAssistantSidebarWidth = (): number => {
  try {
    const parsed = parseInt(localStorage.getItem(WIDTH_STORAGE_KEY) ?? "", 10);
    if (parsed > 0) return parsed;
  } catch {
    /* localStorage unavailable */
  }
  return ASSISTANT_SIDEBAR_DEFAULT_WIDTH;
};

export const isAssistantSidebarOpen = (): boolean => {
  try {
    const stored = localStorage.getItem(OPEN_STORAGE_KEY);
    return stored === null ? true : stored === "true";
  } catch {
    return true;
  }
};

export const setAssistantSidebarOpen = (open: boolean): void => {
  try {
    localStorage.setItem(OPEN_STORAGE_KEY, String(open));
  } catch {
    /* localStorage unavailable */
  }
};
