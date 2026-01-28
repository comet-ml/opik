const VIEW_MODE_KEY = "opik-annotation-view-mode";

/**
 * Simple localStorage utility for view mode preference in annotation queue.
 * This is separate from the data-view storage system.
 */
export const viewModeStorage = {
  /**
   * Get the current view mode preference for annotation queue
   * Defaults to 'classic' if not set
   */
  getViewMode(): "classic" | "custom" {
    try {
      const mode = localStorage.getItem(VIEW_MODE_KEY);
      return mode === "custom" ? "custom" : "classic";
    } catch (error) {
      console.error("Failed to get view mode:", error);
      return "classic";
    }
  },

  /**
   * Set the view mode preference for annotation queue
   */
  setViewMode(mode: "classic" | "custom"): void {
    try {
      localStorage.setItem(VIEW_MODE_KEY, mode);
    } catch (error) {
      console.error("Failed to save view mode:", error);
    }
  },
};
