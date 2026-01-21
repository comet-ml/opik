import { CustomViewSchema } from "@/types/custom-view";

const STORAGE_KEY_PREFIX = "opik-custom-view";
const VIEW_MODE_KEY = "opik-annotation-view-mode";

/**
 * localStorage utility for managing custom view schemas and view mode preferences
 */
export const customViewStorage = {
  /**
   * Save a custom view schema for a project
   */
  save(projectId: string, schema: CustomViewSchema): void {
    try {
      const key = `${STORAGE_KEY_PREFIX}:${projectId}`;
      localStorage.setItem(key, JSON.stringify(schema));
    } catch (error) {
      console.error("Failed to save custom view schema:", error);
      throw error;
    }
  },

  /**
   * Load a custom view schema for a project
   * Returns null if not found or if parsing fails
   */
  load(projectId: string): CustomViewSchema | null {
    try {
      const key = `${STORAGE_KEY_PREFIX}:${projectId}`;
      const item = localStorage.getItem(key);
      if (!item) return null;
      return JSON.parse(item) as CustomViewSchema;
    } catch (error) {
      console.error("Failed to load custom view schema:", error);
      return null;
    }
  },

  /**
   * Delete a custom view schema for a project
   */
  delete(projectId: string): void {
    try {
      const key = `${STORAGE_KEY_PREFIX}:${projectId}`;
      localStorage.removeItem(key);
    } catch (error) {
      console.error("Failed to delete custom view schema:", error);
    }
  },

  /**
   * Check if a custom view schema exists for a project
   */
  exists(projectId: string): boolean {
    try {
      const key = `${STORAGE_KEY_PREFIX}:${projectId}`;
      return localStorage.getItem(key) !== null;
    } catch (error) {
      console.error("Failed to check custom view schema existence:", error);
      return false;
    }
  },

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
