import { describe, it, expect, beforeEach, vi } from "vitest";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  act,
} from "@testing-library/react";
import { ThemeProvider, useTheme } from "../ThemeProvider";

// Mock the utils
vi.mock("@/lib/themes/utils", () => ({
  calculateThemeMode: vi.fn((theme) => {
    if (theme === "system") {
      return window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark"
        : "light";
    }
    return theme;
  }),
  getStoredThemePreferences: vi.fn(() => ({
    mode: "system",
    variant: "default",
  })),
  storeThemePreferences: vi.fn(),
  applyThemeToDocument: vi.fn(),
}));

// Mock localStorage
const localStorageMock = {
  getItem: vi.fn(),
  setItem: vi.fn(),
};
Object.defineProperty(window, "localStorage", { value: localStorageMock });

// Mock matchMedia
const matchMediaMock = vi.fn(() => ({
  matches: false,
  addEventListener: vi.fn(),
  removeEventListener: vi.fn(),
  addListener: vi.fn(),
  removeListener: vi.fn(),
}));
Object.defineProperty(window, "matchMedia", { value: matchMediaMock });

// Test component that uses the theme
const TestComponent = () => {
  const { theme, themeMode, variant, setTheme, setVariant } = useTheme();

  return (
    <div>
      <div data-testid="theme">{theme}</div>
      <div data-testid="theme-mode">{themeMode}</div>
      <div data-testid="variant">{variant}</div>
      <button onClick={() => setTheme("dark")} data-testid="set-dark">
        Set Dark
      </button>
      <button onClick={() => setVariant("midnight")} data-testid="set-midnight">
        Set Midnight
      </button>
    </div>
  );
};

describe("ThemeProvider", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    matchMediaMock.mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
    });
  });

  it("should provide theme context values", () => {
    render(
      <ThemeProvider>
        <TestComponent />
      </ThemeProvider>,
    );

    expect(screen.getByTestId("theme")).toHaveTextContent("system");
    expect(screen.getByTestId("theme-mode")).toHaveTextContent("light");
    expect(screen.getByTestId("variant")).toHaveTextContent("default");
  });

  it("should update theme when setTheme is called", async () => {
    render(
      <ThemeProvider>
        <TestComponent />
      </ThemeProvider>,
    );

    fireEvent.click(screen.getByTestId("set-dark"));

    await waitFor(() => {
      expect(screen.getByTestId("theme")).toHaveTextContent("dark");
      expect(screen.getByTestId("theme-mode")).toHaveTextContent("dark");
    });
  });

  it("should update variant when setVariant is called", async () => {
    render(
      <ThemeProvider>
        <TestComponent />
      </ThemeProvider>,
    );

    fireEvent.click(screen.getByTestId("set-midnight"));

    await waitFor(() => {
      expect(screen.getByTestId("variant")).toHaveTextContent("midnight");
    });
  });

  it("should respond to system theme changes", async () => {
    const mockMediaQuery = {
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
    };
    matchMediaMock.mockReturnValue(mockMediaQuery);

    render(
      <ThemeProvider>
        <TestComponent />
      </ThemeProvider>,
    );

    // Simulate system theme change to dark
    mockMediaQuery.matches = true;
    const changeHandler = mockMediaQuery.addEventListener.mock.calls[0]?.[1];
    if (changeHandler) {
      act(() => {
        changeHandler();
      });
    }

    await waitFor(() => {
      expect(screen.getByTestId("theme-mode")).toHaveTextContent("dark");
    });
  });

  it("should throw error when used outside provider", () => {
    const TestComponentWithoutProvider = () => {
      useTheme();
      return <div>Should not render</div>;
    };

    // Suppress console.error for this test
    const consoleSpy = vi.spyOn(console, "error").mockImplementation(() => {});

    expect(() => render(<TestComponentWithoutProvider />)).toThrow();

    consoleSpy.mockRestore();
  });

  it("should use default props when provided", () => {
    render(
      <ThemeProvider defaultTheme="dark" defaultVariant="high-contrast">
        <TestComponent />
      </ThemeProvider>,
    );

    expect(screen.getByTestId("theme")).toHaveTextContent("dark");
    expect(screen.getByTestId("theme-mode")).toHaveTextContent("dark");
    expect(screen.getByTestId("variant")).toHaveTextContent("high-contrast");
  });
});
