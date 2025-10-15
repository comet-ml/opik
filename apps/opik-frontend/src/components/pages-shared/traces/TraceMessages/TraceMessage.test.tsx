import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import TraceMessage from "./TraceMessage";
import { Trace } from "@/types/traces";

// Mock the components that are not relevant for this test
vi.mock("@/components/shared/MessageRenderer", () => ({
  MessageRenderer: ({ message }: { message: unknown }) => (
    <div data-testid="message-renderer">{JSON.stringify(message)}</div>
  ),
}));

vi.mock("@/components/pages-shared/traces/TraceMessages/LikeFeedback", () => ({
  default: () => <div data-testid="like-feedback">Like Feedback</div>,
}));

vi.mock(
  "@/components/pages-shared/traces/TraceDetailsPanel/BaseTraceDataTypeIcon",
  () => ({
    default: ({ type }: { type: string }) => (
      <div data-testid="tool-icon">{type}</div>
    ),
  }),
);

describe("TraceMessage", () => {
  const createMockTrace = (
    input: unknown,
    output: unknown,
    overrides?: Partial<Trace>,
  ): Trace => ({
    id: "trace_123",
    name: "test_trace",
    input,
    output,
    start_time: "2024-01-01T00:00:00Z",
    end_time: "2024-01-01T00:00:01Z",
    duration: 1000,
    created_at: "2024-01-01T00:00:00Z",
    last_updated_at: "2024-01-01T00:00:01Z",
    metadata: {},
    project_id: "project_123",
    comments: { total: 0, comments: [] },
    tags: [],
    ...overrides,
  });

  it("should render trace without tool calls", () => {
    const trace = createMockTrace(
      { content: "Hello" },
      { content: "Hi there!" },
    );

    render(<TraceMessage trace={trace} />);

    // Should render message content
    expect(screen.getAllByTestId("message-renderer")).toHaveLength(2);

    // Should NOT render tool icons
    expect(screen.queryAllByTestId("tool-icon")).toHaveLength(0);
  });

  it("should render tool icon when trace has tool calls in output", () => {
    const trace = createMockTrace(
      { content: "Get the weather" },
      {
        messages: [
          {
            role: "assistant",
            content: null,
            tool_calls: [
              {
                id: "call_123",
                type: "function",
                function: {
                  name: "get_weather",
                  arguments: '{"location": "Boston"}',
                },
              },
            ],
          },
        ],
      },
    );

    render(<TraceMessage trace={trace} />);

    // Should render tool icons for both input and output messages
    const toolIcons = screen.getAllByTestId("tool-icon");
    expect(toolIcons).toHaveLength(2);
    expect(toolIcons[0]).toHaveTextContent("tool");
    expect(toolIcons[1]).toHaveTextContent("tool");
  });

  it("should render tool icon when trace has tool calls in input", () => {
    const trace = createMockTrace(
      {
        tool_calls: [
          {
            id: "call_456",
            type: "function",
            function: { name: "search", arguments: '{"query": "test"}' },
          },
        ],
      },
      { content: "Search results" },
    );

    render(<TraceMessage trace={trace} />);

    // Should render tool icons
    const toolIcons = screen.getAllByTestId("tool-icon");
    expect(toolIcons.length).toBeGreaterThan(0);
  });

  it("should render tool icon when trace has tool role messages", () => {
    const trace = createMockTrace(
      { content: "User input" },
      {
        messages: [
          {
            role: "assistant",
            content: null,
            tool_calls: [
              {
                id: "call_789",
                type: "function",
                function: { name: "calculate", arguments: '{"expr": "2+2"}' },
              },
            ],
          },
          {
            role: "tool",
            tool_call_id: "call_789",
            content: "4",
          },
          {
            role: "assistant",
            content: "The answer is 4",
          },
        ],
      },
    );

    render(<TraceMessage trace={trace} />);

    // Should render tool icons
    const toolIcons = screen.getAllByTestId("tool-icon");
    expect(toolIcons).toHaveLength(2);
  });

  it("should render action buttons when handleOpenTrace is provided", () => {
    const trace = createMockTrace({ content: "Input" }, { content: "Output" });
    const handleOpenTrace = vi.fn();

    render(<TraceMessage trace={trace} handleOpenTrace={handleOpenTrace} />);

    // Should render like feedback and view trace button
    expect(screen.getByTestId("like-feedback")).toBeInTheDocument();
    expect(screen.getByText("View trace")).toBeInTheDocument();
  });

  it("should not render action buttons when handleOpenTrace is not provided", () => {
    const trace = createMockTrace({ content: "Input" }, { content: "Output" });

    render(<TraceMessage trace={trace} />);

    // Should NOT render action buttons
    expect(screen.queryByTestId("like-feedback")).not.toBeInTheDocument();
    expect(screen.queryByText("View trace")).not.toBeInTheDocument();
  });

  it("should handle complex tool call structures", () => {
    const trace = createMockTrace(
      {
        messages: [{ role: "user", content: "Book a flight and a hotel" }],
      },
      {
        messages: [
          {
            role: "assistant",
            content: null,
            tool_calls: [
              {
                id: "call_flight",
                type: "function",
                function: {
                  name: "book_flight",
                  arguments: '{"from": "NYC", "to": "LAX"}',
                },
              },
              {
                id: "call_hotel",
                type: "function",
                function: {
                  name: "book_hotel",
                  arguments: '{"location": "LAX", "nights": 3}',
                },
              },
            ],
          },
          {
            role: "tool",
            tool_call_id: "call_flight",
            content: "Flight booked: AA123",
          },
          {
            role: "tool",
            tool_call_id: "call_hotel",
            content: "Hotel booked: Hilton LAX",
          },
          {
            role: "assistant",
            content: "I've booked your flight (AA123) and hotel (Hilton LAX).",
          },
        ],
      },
    );

    render(<TraceMessage trace={trace} />);

    // Should render tool icons for messages with multiple tool calls
    const toolIcons = screen.getAllByTestId("tool-icon");
    expect(toolIcons.length).toBeGreaterThan(0);
  });

  it("should render correctly with empty tool_calls array", () => {
    const trace = createMockTrace(
      { content: "Input" },
      {
        messages: [
          {
            role: "assistant",
            content: "Output",
            tool_calls: [], // Empty array should not trigger tool icon
          },
        ],
      },
    );

    render(<TraceMessage trace={trace} />);

    // Should NOT render tool icons for empty tool_calls array
    expect(screen.queryAllByTestId("tool-icon")).toHaveLength(0);
  });

  it("should position tool icons correctly", () => {
    const trace = createMockTrace(
      { content: "Input" },
      {
        tool_calls: [
          {
            id: "call_1",
            type: "function",
            function: { name: "tool", arguments: "{}" },
          },
        ],
      },
    );

    const { container } = render(<TraceMessage trace={trace} />);

    // Check that tool icons have the correct positioning classes
    const iconContainers = container.querySelectorAll(
      '[class*="absolute"][class*="-left-7"]',
    );
    expect(iconContainers.length).toBeGreaterThan(0);
  });
});
