import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  calculateThemeMode,
  getStoredThemePreferences,
  storeThemePreferences,
  applyThemeToDocument,
} from '../utils';
import { ThemePreferences, DEFAULT_THEME_PREFERENCES } from '../types';

// Mock localStorage
const localStorageMock = {
  getItem: vi.fn(),
  setItem: vi.fn(),
  removeItem: vi.fn(),
  clear: vi.fn(),
};
Object.defineProperty(window, 'localStorage', { value: localStorageMock });

// Mock matchMedia
const matchMediaMock = vi.fn();
Object.defineProperty(window, 'matchMedia', { value: matchMediaMock });

// Mock document
const mockClassList = {
  add: vi.fn(),
  remove: vi.fn(),
};
Object.defineProperty(document, 'documentElement', {
  value: {
    classList: mockClassList,
  },
  configurable: true,
});

describe('Theme Utils', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorageMock.getItem.mockReturnValue(null);
  });

  describe('calculateThemeMode', () => {
    it('should return light for light theme', () => {
      expect(calculateThemeMode('light')).toBe('light');
    });

    it('should return dark for dark theme', () => {
      expect(calculateThemeMode('dark')).toBe('dark');
    });

    it('should return dark for system when system prefers dark', () => {
      matchMediaMock.mockReturnValue({ matches: true });
      expect(calculateThemeMode('system')).toBe('dark');
    });

    it('should return light for system when system prefers light', () => {
      matchMediaMock.mockReturnValue({ matches: false });
      expect(calculateThemeMode('system')).toBe('light');
    });
  });

  describe('localStorage integration', () => {
    it('should return default preferences when localStorage is empty', () => {
      const preferences = getStoredThemePreferences();
      expect(preferences).toEqual(DEFAULT_THEME_PREFERENCES);
    });

    it('should parse and merge stored preferences', () => {
      const stored = { mode: 'dark', variant: 'midnight' };
      localStorageMock.getItem.mockReturnValue(JSON.stringify(stored));

      const preferences = getStoredThemePreferences();
      expect(preferences).toEqual({
        ...DEFAULT_THEME_PREFERENCES,
        ...stored,
      });
    });

    it('should handle invalid JSON gracefully', () => {
      localStorageMock.getItem.mockReturnValue('invalid-json');
      const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

      const preferences = getStoredThemePreferences();
      expect(preferences).toEqual(DEFAULT_THEME_PREFERENCES);
      expect(consoleSpy).toHaveBeenCalled();

      consoleSpy.mockRestore();
    });

    it('should store preferences to localStorage', () => {
      const preferences: ThemePreferences = {
        mode: 'dark',
        variant: 'high-contrast',
      };

      storeThemePreferences(preferences);
      expect(localStorageMock.setItem).toHaveBeenCalledWith(
        'opik-theme-preferences',
        JSON.stringify(preferences)
      );
    });

    it('should handle localStorage setItem errors', () => {
      localStorageMock.setItem.mockImplementation(() => {
        throw new Error('Storage full');
      });
      const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

      const preferences: ThemePreferences = { mode: 'dark', variant: 'default' };
      storeThemePreferences(preferences);

      expect(consoleSpy).toHaveBeenCalled();
      consoleSpy.mockRestore();
    });
  });

  describe('applyThemeToDocument', () => {
    it('should apply light theme without variant', () => {
      applyThemeToDocument('light', 'default');

      expect(mockClassList.remove).toHaveBeenCalledWith('light', 'dark');
      expect(mockClassList.remove).toHaveBeenCalledWith(
        'theme-default',
        'theme-high-contrast',
        'theme-midnight'
      );
      expect(mockClassList.add).toHaveBeenCalledWith('light');
      expect(mockClassList.add).not.toHaveBeenCalledWith('theme-default');
    });

    it('should apply dark theme with variant', () => {
      applyThemeToDocument('dark', 'high-contrast');

      expect(mockClassList.remove).toHaveBeenCalledWith('light', 'dark');
      expect(mockClassList.remove).toHaveBeenCalledWith(
        'theme-default',
        'theme-high-contrast',
        'theme-midnight'
      );
      expect(mockClassList.add).toHaveBeenCalledWith('dark');
      expect(mockClassList.add).toHaveBeenCalledWith('theme-high-contrast');
    });

    it('should apply midnight variant', () => {
      applyThemeToDocument('dark', 'midnight');

      expect(mockClassList.add).toHaveBeenCalledWith('dark');
      expect(mockClassList.add).toHaveBeenCalledWith('theme-midnight');
    });
  });
});