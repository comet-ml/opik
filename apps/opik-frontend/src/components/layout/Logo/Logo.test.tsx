import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import Logo from "./Logo";

// Mock the theme provider
const mockUseTheme = vi.fn();
vi.mock("@/components/theme-provider", () => ({
  useTheme: () => mockUseTheme(),
}));

describe("Logo", () => {
  it("should render light logo by default", () => {
    mockUseTheme.mockReturnValue({ themeMode: "light" });

    render(<Logo expanded={true} />);

    const logo = screen.getByAltText("opik logo");
    expect(logo).toBeInTheDocument();
    expect(logo).toHaveAttribute("src", "/images/opik-logo.png");
  });

  it("should render dark logo when theme is dark", () => {
    mockUseTheme.mockReturnValue({ themeMode: "dark" });

    render(<Logo expanded={true} />);

    const logo = screen.getByAltText("opik logo");
    expect(logo).toBeInTheDocument();
    expect(logo).toHaveAttribute("src", "/images/opik-logo-dark-alt.png");
  });

  it("should apply correct classes when expanded", () => {
    mockUseTheme.mockReturnValue({ themeMode: "light" });

    render(<Logo expanded={true} />);

    const logo = screen.getByAltText("opik logo");
    expect(logo).toHaveClass(
      "h-8",
      "object-cover",
      "object-left",
      "-ml-[3px]",
      "mr-[3px]",
    );
    expect(logo).not.toHaveClass("w-[32px]");
  });

  it("should apply correct classes when collapsed", () => {
    mockUseTheme.mockReturnValue({ themeMode: "light" });

    render(<Logo expanded={false} />);

    const logo = screen.getByAltText("opik logo");
    expect(logo).toHaveClass(
      "h-8",
      "object-cover",
      "object-left",
      "-ml-[3px]",
      "mr-[3px]",
      "w-[32px]",
    );
  });

  it("should switch logo based on theme mode changes", () => {
    // Start with light theme
    mockUseTheme.mockReturnValue({ themeMode: "light" });
    const { rerender } = render(<Logo expanded={true} />);

    let logo = screen.getByAltText("opik logo");
    expect(logo).toHaveAttribute("src", "/images/opik-logo.png");

    // Switch to dark theme
    mockUseTheme.mockReturnValue({ themeMode: "dark" });
    rerender(<Logo expanded={true} />);

    logo = screen.getByAltText("opik logo");
    expect(logo).toHaveAttribute("src", "/images/opik-logo-dark-alt.png");
  });
});
