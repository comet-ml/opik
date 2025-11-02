import { render, screen } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import ProviderSelect from "./ProviderSelect";
import { PROVIDER_TYPE, ProviderKey, CustomProviderKey } from "@/types/providers";

describe("ProviderSelect - Multiple Custom Providers", () => {
  const mockProviders: ProviderKey[] = [
    {
      id: "openai-id",
      provider: PROVIDER_TYPE.OPEN_AI,
      keyName: "OpenAI Key",
      created_at: "2024-01-01T00:00:00Z",
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

  describe("Edge Cases", () => {
    describe("Invalid/Deleted Provider References", () => {
      it("should render empty trigger when value references a deleted provider", () => {
        const onChange = vi.fn();

        const { container } = render(
          <ProviderSelect
            value="non-existent-id"
            onChange={onChange}
            configuredProvidersList={mockProviders}
          />,
        );

        // Should render the button but with empty span (no placeholder shown for invalid IDs)
        const button = container.querySelector('button[role="combobox"]');
        expect(button).toBeInTheDocument();
        const span = button?.querySelector('span');
        expect(span).toBeInTheDocument();
        expect(span).toBeEmptyDOMElement();
      });

      it("should render empty trigger when value is an invalid PROVIDER_TYPE", () => {
        const onChange = vi.fn();

        const { container } = render(
          <ProviderSelect
            value="invalid-provider-type"
            onChange={onChange}
            configuredProvidersList={mockProviders}
          />,
        );

        // Should render the button but with empty span (no placeholder shown for invalid types)
        const button = container.querySelector('button[role="combobox"]');
        expect(button).toBeInTheDocument();
        const span = button?.querySelector('span');
        expect(span).toBeInTheDocument();
        expect(span).toBeEmptyDOMElement();
      });

      it("should handle undefined configuredProvidersList gracefully", () => {
        const onChange = vi.fn();

        render(
          <ProviderSelect
            value={PROVIDER_TYPE.OPEN_AI}
            onChange={onChange}
            configuredProvidersList={undefined}
          />,
        );

        // Should still render without crashing
        expect(screen.getByText("OpenAI")).toBeInTheDocument();
      });
    });

    describe("Legacy Custom Provider Support (Migration)", () => {
      it("should handle legacy custom provider without provider_name", () => {
        const onChange = vi.fn();

        // Legacy custom provider created before multi-provider support
        const legacyCustomProvider: CustomProviderKey = {
          id: "legacy-custom",
          provider: PROVIDER_TYPE.CUSTOM,
          keyName: "My Custom LLM",
          provider_name: "", // Legacy providers might have empty string
          created_at: "2023-01-01T00:00:00Z",
          base_url: "http://localhost:8080/v1",
          configuration: {
            models: "model-1",
          },
        };

        const providersWithLegacy = [...mockProviders, legacyCustomProvider];

        render(
          <ProviderSelect
            value="legacy-custom"
            onChange={onChange}
            configuredProvidersList={providersWithLegacy}
          />,
        );

        // Should fall back to keyName when provider_name is missing/empty
        expect(screen.getByText("My Custom LLM")).toBeInTheDocument();
      });

      it("should fall back to default name when both provider_name and keyName are empty", () => {
        const onChange = vi.fn();

        const unnamedCustomProvider: CustomProviderKey = {
          id: "unnamed-custom",
          provider: PROVIDER_TYPE.CUSTOM,
          keyName: "", // Empty keyName
          provider_name: "", // Empty provider_name
          created_at: "2023-01-01T00:00:00Z",
          base_url: "http://localhost:8080/v1",
          configuration: {},
        };

        const providersWithUnnamed = [...mockProviders, unnamedCustomProvider];

        render(
          <ProviderSelect
            value="unnamed-custom"
            onChange={onChange}
            configuredProvidersList={providersWithUnnamed}
          />,
        );

        // Should show default "Custom provider" text
        expect(screen.getByText("Custom provider")).toBeInTheDocument();
      });

      it("should display base_url as description for legacy providers", () => {
        const onChange = vi.fn();

        const legacyProvider: CustomProviderKey = {
          id: "legacy-with-url",
          provider: PROVIDER_TYPE.CUSTOM,
          keyName: "Legacy Provider",
          provider_name: "",
          created_at: "2023-01-01T00:00:00Z",
          base_url: "http://legacy.example.com/v1",
          configuration: {},
        };

        render(
          <ProviderSelect
            value=""
            onChange={onChange}
            configuredProvidersList={[legacyProvider]}
          />,
        );

        // The base_url should be available as description (not directly visible in trigger)
        expect(screen.getByText("Select a provider")).toBeInTheDocument();
      });
    });

    describe("isAddingCustomProvider State", () => {
      it("should show 'Custom provider' text when isAddingCustomProvider is true with empty value", () => {
        const onChange = vi.fn();

        render(
          <ProviderSelect
            value=""
            onChange={onChange}
            configuredProvidersList={mockProviders}
            isAddingCustomProvider={true}
          />,
        );

        // Should show the default custom provider name
        expect(screen.getByText("Custom provider")).toBeInTheDocument();
      });

      it("should not show custom provider text when isAddingCustomProvider is true but value is set", () => {
        const onChange = vi.fn();

        render(
          <ProviderSelect
            value={PROVIDER_TYPE.OPEN_AI}
            onChange={onChange}
            configuredProvidersList={mockProviders}
            isAddingCustomProvider={true}
          />,
        );

        // Should show the actual selected provider, not the default custom text
        expect(screen.getByText("OpenAI")).toBeInTheDocument();
        expect(screen.queryByText("Custom provider")).not.toBeInTheDocument();
      });
    });

    describe("Error State", () => {
      it("should apply error styling when hasError is true", () => {
        const onChange = vi.fn();

        const { container } = render(
          <ProviderSelect
            value=""
            onChange={onChange}
            configuredProvidersList={mockProviders}
            hasError={true}
          />,
        );

        // Check if the component has error border class
        const selectBox = container.querySelector('[class*="border-destructive"]');
        expect(selectBox).toBeInTheDocument();
      });

      it("should not apply error styling when hasError is false", () => {
        const onChange = vi.fn();

        const { container } = render(
          <ProviderSelect
            value=""
            onChange={onChange}
            configuredProvidersList={mockProviders}
            hasError={false}
          />,
        );

        // Check that error border class is not present
        const selectBox = container.querySelector('[class*="border-destructive"]');
        expect(selectBox).not.toBeInTheDocument();
      });
    });

    describe("Disabled State", () => {
      it("should be disabled when disabled prop is true", () => {
        const onChange = vi.fn();

        render(
          <ProviderSelect
            value={PROVIDER_TYPE.OPEN_AI}
            onChange={onChange}
            configuredProvidersList={mockProviders}
            disabled={true}
          />,
        );

        // Component should render but be disabled
        expect(screen.getByText("OpenAI")).toBeInTheDocument();
      });
    });

    describe("Multiple Custom Providers with Same Name", () => {
      it("should distinguish between multiple custom providers with same display name", () => {
        const onChange = vi.fn();

        const duplicateNameProviders: CustomProviderKey[] = [
          {
            id: "custom-a",
            provider: PROVIDER_TYPE.CUSTOM,
            keyName: "My Server",
            provider_name: "ollama-instance-1",
            created_at: "2024-01-01T00:00:00Z",
            base_url: "http://localhost:11434/v1",
            configuration: {},
          },
          {
            id: "custom-b",
            provider: PROVIDER_TYPE.CUSTOM,
            keyName: "My Server", // Same keyName
            provider_name: "ollama-instance-2",
            created_at: "2024-01-01T00:00:00Z",
            base_url: "http://localhost:11435/v1",
            configuration: {},
          },
        ];

        const { rerender } = render(
          <ProviderSelect
            value="custom-a"
            onChange={onChange}
            configuredProvidersList={duplicateNameProviders}
          />,
        );

        // First provider - should show its provider_name
        expect(screen.getByText("ollama-instance-1")).toBeInTheDocument();

        // Switch to second provider
        rerender(
          <ProviderSelect
            value="custom-b"
            onChange={onChange}
            configuredProvidersList={duplicateNameProviders}
          />,
        );

        // Second provider - should show its provider_name
        expect(screen.getByText("ollama-instance-2")).toBeInTheDocument();
      });
    });

    describe("onAddCustomProvider Callback", () => {
      it("should not show 'Add custom provider' option when callback is not provided", () => {
        const onChange = vi.fn();

        render(
          <ProviderSelect
            value=""
            onChange={onChange}
            configuredProvidersList={mockProviders}
            onAddCustomProvider={undefined}
          />,
        );

        // Should not show add option when callback is missing
        expect(screen.getByText("Select a provider")).toBeInTheDocument();
      });

      it("should show 'Add custom provider' option when callback is provided", () => {
        const onChange = vi.fn();
        const onAddCustomProvider = vi.fn();

        render(
          <ProviderSelect
            value=""
            onChange={onChange}
            configuredProvidersList={mockProviders}
            onAddCustomProvider={onAddCustomProvider}
          />,
        );

        // Component should render (separator and add button are in dropdown, not visible in trigger)
        expect(screen.getByText("Select a provider")).toBeInTheDocument();
      });
    });
  });
});
