import React from "react";
import { render, screen, act } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { ThemeProvider, useTheme } from "./theme-provider";

// Mock localStorage
const localStorageMock = {
  getItem: vi.fn(),
  setItem: vi.fn(),
  removeItem: vi.fn(),
  clear: vi.fn(),
};

// Mock window.matchMedia
const matchMediaMock = vi.fn(() => ({
  matches: false,
  addEventListener: vi.fn(),
  removeEventListener: vi.fn(),
}));

// Test component to access theme context
const TestComponent = () => {
  const { theme, themeMode, setTheme } = useTheme();
  return (
    <div>
      <div data-testid="theme">{theme}</div>
      <div data-testid="themeMode">{themeMode}</div>
      <button onClick={() => setTheme("dark")} data-testid="set-dark">
        Set Dark
      </button>
      <button onClick={() => setTheme("light")} data-testid="set-light">
        Set Light
      </button>
      <button onClick={() => setTheme("system")} data-testid="set-system">
        Set System
      </button>
    </div>
  );
};

describe("ThemeProvider", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.defineProperty(window, "localStorage", { value: localStorageMock });
    Object.defineProperty(window, "matchMedia", { value: matchMediaMock });

    // Mock document.documentElement
    Object.defineProperty(document, "documentElement", {
      value: {
        classList: {
          add: vi.fn(),
          remove: vi.fn(),
        },
      },
      writable: true,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("should use default theme when no localStorage value", () => {
    localStorageMock.getItem.mockReturnValue(null);
    matchMediaMock.mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    });

    render(
      <ThemeProvider defaultTheme="light">
        <TestComponent />
      </ThemeProvider>,
    );

    expect(screen.getByTestId("theme")).toHaveTextContent("light");
    expect(screen.getByTestId("themeMode")).toHaveTextContent("light");
  });

  it("should use localStorage value when available", () => {
    localStorageMock.getItem.mockReturnValue("dark");

    render(
      <ThemeProvider>
        <TestComponent />
      </ThemeProvider>,
    );

    expect(screen.getByTestId("theme")).toHaveTextContent("dark");
    expect(screen.getByTestId("themeMode")).toHaveTextContent("dark");
  });

  it("should handle system theme preference (dark)", () => {
    localStorageMock.getItem.mockReturnValue("system");
    matchMediaMock.mockReturnValue({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }); // dark mode preference

    render(
      <ThemeProvider>
        <TestComponent />
      </ThemeProvider>,
    );

    expect(screen.getByTestId("theme")).toHaveTextContent("system");
    expect(screen.getByTestId("themeMode")).toHaveTextContent("dark");
  });

  it("should handle system theme preference (light)", () => {
    localStorageMock.getItem.mockReturnValue("system");
    matchMediaMock.mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }); // light mode preference

    render(
      <ThemeProvider>
        <TestComponent />
      </ThemeProvider>,
    );

    expect(screen.getByTestId("theme")).toHaveTextContent("system");
    expect(screen.getByTestId("themeMode")).toHaveTextContent("light");
  });

  it("should update theme when setTheme is called", async () => {
    localStorageMock.getItem.mockReturnValue("light");

    render(
      <ThemeProvider>
        <TestComponent />
      </ThemeProvider>,
    );

    expect(screen.getByTestId("theme")).toHaveTextContent("light");

    await act(async () => {
      screen.getByTestId("set-dark").click();
    });

    expect(screen.getByTestId("theme")).toHaveTextContent("dark");
    expect(screen.getByTestId("themeMode")).toHaveTextContent("dark");
    expect(localStorageMock.setItem).toHaveBeenCalledWith(
      "vite-ui-theme",
      "dark",
    );
  });

  it("should apply theme class to document element", () => {
    localStorageMock.getItem.mockReturnValue("dark");
    const mockClassList = {
      add: vi.fn(),
      remove: vi.fn(),
    };

    Object.defineProperty(document, "documentElement", {
      value: { classList: mockClassList },
      writable: true,
    });

    render(
      <ThemeProvider>
        <TestComponent />
      </ThemeProvider>,
    );

    expect(mockClassList.remove).toHaveBeenCalledWith("light", "dark");
    expect(mockClassList.add).toHaveBeenCalledWith("dark");
  });

  it("should use custom storage key", async () => {
    localStorageMock.getItem.mockReturnValue("light");

    render(
      <ThemeProvider storageKey="custom-theme-key">
        <TestComponent />
      </ThemeProvider>,
    );

    await act(async () => {
      screen.getByTestId("set-dark").click();
    });

    expect(localStorageMock.setItem).toHaveBeenCalledWith(
      "custom-theme-key",
      "dark",
    );
  });

  // Note: Testing the error case when useTheme is used outside provider
  // is complex with current testing setup. The main functionality is covered
  // by the other tests which verify that the theme provider works correctly.
});
