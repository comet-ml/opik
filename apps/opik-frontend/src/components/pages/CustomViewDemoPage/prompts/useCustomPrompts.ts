/**
 * Hook for managing custom prompt configurations with localStorage persistence
 */

import { useState, useCallback, useEffect } from "react";
import { CustomPromptConfig, UseCustomPromptsReturn } from "./types";
import { getPromptsStorageKey } from "./promptConstants";

/**
 * Load custom prompts from localStorage
 */
const loadPrompts = (projectId: string): CustomPromptConfig => {
  try {
    const key = getPromptsStorageKey(projectId);
    const stored = localStorage.getItem(key);
    if (stored) {
      return JSON.parse(stored) as CustomPromptConfig;
    }
  } catch (error) {
    console.error("Failed to load custom prompts:", error);
  }
  return {
    conversationalPrompt: null,
    schemaGenerationPrompt: null,
  };
};

/**
 * Save custom prompts to localStorage
 */
const savePrompts = (projectId: string, config: CustomPromptConfig): void => {
  try {
    const key = getPromptsStorageKey(projectId);
    // Only store if at least one prompt is customized
    if (config.conversationalPrompt || config.schemaGenerationPrompt) {
      localStorage.setItem(key, JSON.stringify(config));
    } else {
      // Remove storage if all prompts are reset to default
      localStorage.removeItem(key);
    }
  } catch (error) {
    console.error("Failed to save custom prompts:", error);
  }
};

/**
 * Hook for managing custom AI prompts with localStorage persistence
 *
 * @param projectId - Project ID for scoped storage
 * @returns Custom prompts state and setters
 */
export const useCustomPrompts = (
  projectId: string | undefined,
): UseCustomPromptsReturn => {
  const [config, setConfig] = useState<CustomPromptConfig>(() => {
    if (!projectId) {
      return {
        conversationalPrompt: null,
        schemaGenerationPrompt: null,
      };
    }
    return loadPrompts(projectId);
  });

  // Reload prompts when projectId changes
  useEffect(() => {
    if (projectId) {
      setConfig(loadPrompts(projectId));
    } else {
      setConfig({
        conversationalPrompt: null,
        schemaGenerationPrompt: null,
      });
    }
  }, [projectId]);

  // Save to localStorage whenever config changes
  useEffect(() => {
    if (projectId) {
      savePrompts(projectId, config);
    }
  }, [projectId, config]);

  const setConversationalPrompt = useCallback((prompt: string | null) => {
    setConfig((prev) => ({
      ...prev,
      conversationalPrompt: prompt,
    }));
  }, []);

  const setSchemaGenerationPrompt = useCallback((prompt: string | null) => {
    setConfig((prev) => ({
      ...prev,
      schemaGenerationPrompt: prompt,
    }));
  }, []);

  const resetAll = useCallback(() => {
    setConfig({
      conversationalPrompt: null,
      schemaGenerationPrompt: null,
    });
  }, []);

  return {
    conversationalPrompt: config.conversationalPrompt,
    schemaGenerationPrompt: config.schemaGenerationPrompt,
    isConversationalCustomized: config.conversationalPrompt !== null,
    isSchemaGenerationCustomized: config.schemaGenerationPrompt !== null,
    setConversationalPrompt,
    setSchemaGenerationPrompt,
    resetAll,
  };
};

export default useCustomPrompts;
