import React from "react";
import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { useForm, UseFormReturn } from "react-hook-form";

import CloudAIProviderDetails from "./CloudAIProviderDetails";
import { AIProviderFormType, OpenAiPipelineMode } from "./schema";
import { PROVIDER_TYPE } from "@/types/providers";
import { Form } from "@/ui/form";
import { TooltipProvider } from "@/ui/tooltip";

const renderDetails = (
  provider: PROVIDER_TYPE,
  openaiPipelineMode: OpenAiPipelineMode = "chat_completions_api",
) => {
  const rendered: { form?: UseFormReturn<AIProviderFormType> } = {};
  const Harness = () => {
    const form = useForm<AIProviderFormType>({
      defaultValues: {
        provider,
        composedProviderType: provider,
        apiKey: "",
        headers: [],
        openaiPipelineMode,
      },
    });
    rendered.form = form;
    return (
      <TooltipProvider>
        <Form {...form}>
          <CloudAIProviderDetails provider={provider} form={form} />
        </Form>
      </TooltipProvider>
    );
  };
  render(<Harness />);
  return rendered.form!;
};

describe("the OpenAI API choice on the provider form", () => {
  it("is tucked under Advanced settings, with Chat Completions recommended", () => {
    renderDetails(PROVIDER_TYPE.OPEN_AI);

    expect(screen.queryByText("OpenAI API")).not.toBeInTheDocument();

    fireEvent.click(screen.getByText("Advanced settings"));

    expect(screen.getByText("OpenAI API")).toBeInTheDocument();
    expect(screen.getByRole("combobox")).toHaveTextContent(
      "Chat Completions API (recommended)",
    );
  });

  it("starts open when the form already holds the Responses API", () => {
    renderDetails(PROVIDER_TYPE.OPEN_AI, "responses_api");

    expect(screen.getByRole("combobox")).toHaveTextContent("Responses API");
  });

  it("keeps the chosen API when Advanced settings is collapsed", () => {
    const form = renderDetails(PROVIDER_TYPE.OPEN_AI, "responses_api");

    fireEvent.click(screen.getByText("Advanced settings"));

    expect(screen.queryByText("OpenAI API")).not.toBeInTheDocument();
    expect(form.getValues("openaiPipelineMode")).toBe("responses_api");
  });

  it("is not shown for other providers", () => {
    renderDetails(PROVIDER_TYPE.ANTHROPIC);

    expect(screen.queryByText("Advanced settings")).not.toBeInTheDocument();
  });
});
