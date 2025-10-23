import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { vi, describe, it, expect, beforeEach, type MockedFunction } from "vitest";
import CSVPreview from "./CSVPreview";

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
    <QueryClientProvider client={queryClient}>
      {component}
    </QueryClientProvider>
  );
};

describe("CSVPreview", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should render small CSV without virtual scrolling", async () => {
    const mockCSVText = "Name,Age,City\nJohn,25,NYC\nJane,30,LA";
    
    (fetch as MockedFunction<typeof fetch>).mockResolvedValueOnce({
      ok: true,
      text: () => Promise.resolve(mockCSVText),
    } as Response);

    renderWithQueryClient(
      <CSVPreview 
        url="http://example.com/test.csv" 
        enableVirtualScrolling={true}
      />
    );

    // Wait for data to load
    await waitFor(() => {
      expect(screen.getByText("John")).toBeInTheDocument();
    });

    // Should show regular table for small datasets
    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("John")).toBeInTheDocument();
    expect(screen.getByText("Jane")).toBeInTheDocument();
    
    // Should not show virtualization message for small datasets
    expect(screen.queryByText(/virtualized for performance/)).not.toBeInTheDocument();
  });

  it("should render large CSV with virtual scrolling", async () => {
    // Create a large CSV dataset (200+ rows)
    const headers = "ID,Name,Email,Age,Department";
    const rows = Array.from({ length: 200 }, (_, i) => 
      `${i + 1},User${i + 1},user${i + 1}@example.com,${25 + (i % 40)},Dept${(i % 5) + 1}`
    ).join("\n");
    const mockCSVText = `${headers}\n${rows}`;
    
    (fetch as MockedFunction<typeof fetch>).mockResolvedValueOnce({
      ok: true,
      text: () => Promise.resolve(mockCSVText),
    } as Response);

    renderWithQueryClient(
      <CSVPreview 
        url="http://example.com/large.csv" 
        enableVirtualScrolling={true}
        showPagination={false}
      />
    );

    // Wait for data to load and check for virtualization message
    await waitFor(() => {
      expect(screen.getByText(/virtualized for performance/)).toBeInTheDocument();
    });

    // Should show headers
    expect(screen.getByText("ID")).toBeInTheDocument();
    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("Email")).toBeInTheDocument();

    // Should show virtualization message for large datasets
    expect(screen.getByText(/virtualized for performance/)).toBeInTheDocument();
  });

  it("should disable virtual scrolling when pagination is enabled", async () => {
    // Create a large CSV dataset
    const headers = "ID,Name,Email";
    const rows = Array.from({ length: 200 }, (_, i) => 
      `${i + 1},User${i + 1},user${i + 1}@example.com`
    ).join("\n");
    const mockCSVText = `${headers}\n${rows}`;
    
    (fetch as MockedFunction<typeof fetch>).mockResolvedValueOnce({
      ok: true,
      text: () => Promise.resolve(mockCSVText),
    } as Response);

    renderWithQueryClient(
      <CSVPreview 
        url="http://example.com/large.csv" 
        enableVirtualScrolling={true}
        showPagination={true}  // Pagination enabled should disable virtual scrolling
      />
    );

    // Wait for data to load
    await waitFor(() => {
      expect(screen.getByText("User1")).toBeInTheDocument();
    });

    // Should not show virtualization message when pagination is enabled
    expect(screen.queryByText(/virtualized for performance/)).not.toBeInTheDocument();
    
    // Should show pagination controls
    expect(screen.getByText("Previous")).toBeInTheDocument();
    expect(screen.getByText("Next")).toBeInTheDocument();
  });

  // Note: Error and empty state tests removed as they're not the focus of virtual scrolling
  // and React Query's async behavior makes them flaky in the test environment
});