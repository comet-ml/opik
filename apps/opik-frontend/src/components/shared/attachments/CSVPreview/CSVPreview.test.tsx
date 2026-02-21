import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import CSVPreview from "./CSVPreview";

global.fetch = vi.fn();

const createWrapper = () => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  });
  const Wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  Wrapper.displayName = "QueryClientWrapper";
  return Wrapper;
};

describe("CSVPreview", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should render loader while fetching CSV", () => {
    (global.fetch as ReturnType<typeof vi.fn>).mockImplementation(
      () =>
        new Promise(() => {
          /* never resolves */
        }),
    );

    render(<CSVPreview url="https://example.com/data.csv" />, {
      wrapper: createWrapper(),
    });

    expect(screen.getByText("Loading")).toBeInTheDocument();
  });

  it("should render CSV headers", async () => {
    const csvData = "name,age,city\nAlice,30,New York\nBob,25,London";
    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      text: async () => csvData,
    });

    render(<CSVPreview url="https://example.com/data.csv" />, {
      wrapper: createWrapper(),
    });

    await waitFor(() => {
      expect(screen.getByText("name")).toBeInTheDocument();
      expect(screen.getByText("age")).toBeInTheDocument();
      expect(screen.getByText("city")).toBeInTheDocument();
    });
  });

  it("should handle empty CSV file", async () => {
    const csvData = "";
    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      text: async () => csvData,
    });

    render(<CSVPreview url="https://example.com/data.csv" />, {
      wrapper: createWrapper(),
    });

    await waitFor(() => {
      expect(screen.getByText(/Cannot call csv2json/)).toBeInTheDocument();
    });
  });

  it("should handle fetch error", async () => {
    (global.fetch as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error("Network error"),
    );

    render(<CSVPreview url="https://example.com/data.csv" />, {
      wrapper: createWrapper(),
    });

    await waitFor(() => {
      expect(screen.getByText("Network error")).toBeInTheDocument();
    });
  });

  it("should normalize line endings and render headers", async () => {
    const csvData = "name,age\r\nAlice,30\r\nBob,25";
    (global.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      text: async () => csvData,
    });

    render(<CSVPreview url="https://example.com/data.csv" />, {
      wrapper: createWrapper(),
    });

    await waitFor(() => {
      expect(screen.getByText("name")).toBeInTheDocument();
      expect(screen.getByText("age")).toBeInTheDocument();
    });
  });
});
