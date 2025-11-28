import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  vi,
  describe,
  it,
  expect,
  beforeEach,
  type MockedFunction,
} from "vitest";
import { csv2json } from "json-2-csv";
import CSVPreview from "./CSVPreview";

// Mock the csv2json library
vi.mock("json-2-csv", () => ({
  csv2json: vi.fn(),
}));

// Mock the fetch function
global.fetch = vi.fn() as MockedFunction<typeof fetch>;

// Mock ResizeObserver for virtual scrolling
global.ResizeObserver = vi.fn().mockImplementation(() => ({
  observe: vi.fn(),
  unobserve: vi.fn(),
  disconnect: vi.fn(),
}));

const createQueryClient = () =>
  new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        gcTime: 0,
      },
    },
  });

const renderWithQueryClient = (component: React.ReactElement) => {
  const queryClient = createQueryClient();
  return render(
    <QueryClientProvider client={queryClient}>{component}</QueryClientProvider>,
  );
};

describe("CSVPreview", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should render small CSV without virtual scrolling", async () => {
    const mockCSVText = "Name,Age,City\nJohn,25,NYC\nJane,30,LA";
    const mockParsedData = [
      { Name: "John", Age: "25", City: "NYC" },
      { Name: "Jane", Age: "30", City: "LA" },
    ];

    (fetch as MockedFunction<typeof fetch>).mockResolvedValueOnce({
      ok: true,
      text: () => Promise.resolve(mockCSVText),
    } as Response);

    vi.mocked(csv2json).mockResolvedValueOnce(mockParsedData as never);

    renderWithQueryClient(<CSVPreview url="http://example.com/test.csv" />);

    // Wait for data to load
    await waitFor(() => {
      expect(screen.getByText("John")).toBeInTheDocument();
    });

    // Should show regular table for small datasets
    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("John")).toBeInTheDocument();
    expect(screen.getByText("Jane")).toBeInTheDocument();

    // Should not show virtualization message for small datasets
    expect(
      screen.queryByText(/virtualized for performance/),
    ).not.toBeInTheDocument();
  });

  it("should render large CSV with virtual scrolling", async () => {
    // Create a large CSV dataset (200+ rows)
    const mockParsedData = Array.from({ length: 200 }, (_, i) => ({
      ID: String(i + 1),
      Name: `User${i + 1}`,
      Email: `user${i + 1}@example.com`,
      Age: String(25 + (i % 40)),
      Department: `Dept${(i % 5) + 1}`,
    }));

    const mockCSVText =
      "ID,Name,Email,Age,Department\n" +
      mockParsedData.map((row) => Object.values(row).join(",")).join("\n");

    (fetch as MockedFunction<typeof fetch>).mockResolvedValueOnce({
      ok: true,
      text: () => Promise.resolve(mockCSVText),
    } as Response);

    vi.mocked(csv2json).mockResolvedValueOnce(mockParsedData as never);

    renderWithQueryClient(<CSVPreview url="http://example.com/large.csv" />);

    // Wait for data to load and check for virtualization message
    await waitFor(() => {
      expect(
        screen.getByText(/virtualized for performance/),
      ).toBeInTheDocument();
    });

    // Should show headers
    expect(screen.getByText("ID")).toBeInTheDocument();
    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("Email")).toBeInTheDocument();

    // Should show virtualization message for large datasets
    expect(screen.getByText(/virtualized for performance/)).toBeInTheDocument();
  });

  it("should handle fetch errors gracefully", async () => {
    (fetch as MockedFunction<typeof fetch>).mockRejectedValueOnce(
      new Error("Network error"),
    );

    renderWithQueryClient(<CSVPreview url="http://example.com/error.csv" />);

    // Wait for error state
    await waitFor(() => {
      expect(screen.getByText(/Network error/)).toBeInTheDocument();
    });
  });

  it("should handle parsing errors gracefully", async () => {
    const mockCSVText = "Invalid,CSV\nContent";

    (fetch as MockedFunction<typeof fetch>).mockResolvedValueOnce({
      ok: true,
      text: () => Promise.resolve(mockCSVText),
    } as Response);

    vi.mocked(csv2json).mockRejectedValueOnce(new Error("Parse error"));

    renderWithQueryClient(<CSVPreview url="http://example.com/invalid.csv" />);

    // Wait for error state
    await waitFor(() => {
      expect(screen.getByText(/Parse error/)).toBeInTheDocument();
    });
  });

  it("should support up to 100k rows", async () => {
    // Test with maxRows parameter
    const mockParsedData = Array.from({ length: 150000 }, (_, i) => ({
      ID: String(i + 1),
      Name: `User${i + 1}`,
    }));

    const mockCSVText =
      "ID,Name\n" +
      mockParsedData.map((row) => Object.values(row).join(",")).join("\n");

    (fetch as MockedFunction<typeof fetch>).mockResolvedValueOnce({
      ok: true,
      text: () => Promise.resolve(mockCSVText),
    } as Response);

    vi.mocked(csv2json).mockResolvedValueOnce(mockParsedData as never);

    renderWithQueryClient(
      <CSVPreview url="http://example.com/huge.csv" maxRows={100000} />,
    );

    // Wait for data to load
    await waitFor(() => {
      expect(
        screen.getByText(/Showing first 100,000 rows of 150,000 total rows/),
      ).toBeInTheDocument();
    });

    // Should use virtual scrolling for this large dataset
    expect(screen.getByText(/virtualized for performance/)).toBeInTheDocument();
  });
});
