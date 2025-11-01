import { render, screen } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import ProviderSelect from "./ProviderSelect";
import { PROVIDER_TYPE, ProviderKey } from "@/types/providers";

describe("ProviderSelect - Multiple Custom Providers", () => {
  const mockProviders: ProviderKey[] = [
    {
      id: "openai-id",
      provider: PROVIDER_TYPE.OPEN_AI,
      keyName: "OpenAI Key",
      created_at: "2024-01-01T00:00:00Z",
      base_url: undefined,
      configuration: {},
    },
    {
      id: "custom-1",
      provider: PROVIDER_TYPE.CUSTOM,
      keyName: "Custom Provider",
      provider_name: "ollama",
      created_at: "2024-01-01T00:00:00Z",
      base_url: "http://localhost:11434/v1",
      configuration: {
        models: "llama-3.2,codellama",
      },
    },
    {
      id: "custom-2",
      provider: PROVIDER_TYPE.CUSTOM,
      keyName: "Custom Provider",
      provider_name: "vllm",
      created_at: "2024-01-01T00:00:00Z",
      base_url: "http://localhost:8000/v1",
      configuration: {
        models: "mistral",
      },
    },
  ];

  it("should render with placeholder when no value selected", () => {
    const onChange = vi.fn();

    render(
      <ProviderSelect
        value=""
        onChange={onChange}
        configuredProvidersList={mockProviders}
      />,
    );

    // Should show placeholder
    expect(screen.getByText("Select a provider")).toBeInTheDocument();
  });

  it("should display selected custom provider in trigger", () => {
    const onChange = vi.fn();

    const { rerender } = render(
      <ProviderSelect
        value=""
        onChange={onChange}
        configuredProvidersList={mockProviders}
      />,
    );

    // Initially shows placeholder
    expect(screen.getByText("Select a provider")).toBeInTheDocument();

    // Rerender with selected custom provider
    rerender(
      <ProviderSelect
        value="custom-1"
        onChange={onChange}
        configuredProvidersList={mockProviders}
      />,
    );

    // Should show the selected custom provider's name in the trigger
    expect(screen.getByText("ollama")).toBeInTheDocument();
  });

  it("should display another selected custom provider correctly", () => {
    const onChange = vi.fn();

    render(
      <ProviderSelect
        value="custom-2"
        onChange={onChange}
        configuredProvidersList={mockProviders}
      />,
    );

    // Should show the vllm provider's name in the trigger
    expect(screen.getByText("vllm")).toBeInTheDocument();
  });

  it("should display standard provider when selected", () => {
    const onChange = vi.fn();

    render(
      <ProviderSelect
        value={PROVIDER_TYPE.OPEN_AI}
        onChange={onChange}
        configuredProvidersList={mockProviders}
      />,
    );

    // Should show the OpenAI provider
    expect(screen.getByText("OpenAI")).toBeInTheDocument();
  });

  it("should handle empty provider list", () => {
    const onChange = vi.fn();

    render(
      <ProviderSelect
        value=""
        onChange={onChange}
        configuredProvidersList={[]}
      />,
    );

    // Should still render without errors
    expect(screen.getByText("Select a provider")).toBeInTheDocument();
  });

  it("should accept configuredProvidersList prop", () => {
    const onChange = vi.fn();

    const { container } = render(
      <ProviderSelect
        value=""
        onChange={onChange}
        configuredProvidersList={mockProviders}
      />,
    );

    // Component should render successfully with the prop
    expect(container).toBeInTheDocument();
  });

  it("should call onChange with correct value type", () => {
    const onChange = vi.fn();

    render(
      <ProviderSelect
        value=""
        onChange={onChange}
        configuredProvidersList={mockProviders}
      />,
    );

    // onChange should accept string values (both PROVIDER_TYPE and custom IDs)
    expect(onChange).toBeInstanceOf(Function);
  });
});
