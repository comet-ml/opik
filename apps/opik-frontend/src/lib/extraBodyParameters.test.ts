import { describe, expect, it } from "vitest";

import { getExtraBodySliderOverlap } from "./extraBodyParameters";
import { PROVIDER_TYPE } from "@/types/providers";

const ALL_SLIDERS_SENT = {
  temperature: true,
  top_p: true,
  max_completion_tokens: true,
  frequency_penalty: true,
  presence_penalty: true,
};

describe("getExtraBodySliderOverlap", () => {
  it.each([
    {
      name: "names every slider key the extra body repeats, in slider order",
      extraBody: {
        presence_penalty: 0.1,
        max_completion_tokens: 12,
        temperature: 0.9,
        frequency_penalty: 0.2,
        top_p: 0.5,
      },
      sentSliders: ALL_SLIDERS_SENT,
      provider: PROVIDER_TYPE.CUSTOM,
      expected: [
        "temperature",
        "top_p",
        "max_completion_tokens",
        "frequency_penalty",
        "presence_penalty",
      ],
    },
    {
      name: "skips a key whose slider the request does not send",
      extraBody: { temperature: 0.9, top_p: 0.5, max_tokens: 12 },
      sentSliders: { temperature: true },
      provider: PROVIDER_TYPE.CUSTOM,
      expected: ["temperature"],
    },
    {
      name: "skips keys no slider sets",
      extraBody: { top_k: 7, stop: ["END"] },
      sentSliders: ALL_SLIDERS_SENT,
      provider: PROVIDER_TYPE.CUSTOM,
      expected: [],
    },
    {
      name: "names nothing for a cleared extra body",
      extraBody: null,
      sentSliders: ALL_SLIDERS_SENT,
      provider: PROVIDER_TYPE.CUSTOM,
      expected: [],
    },
    {
      name: "a Custom LLM gets both token limit names as typed",
      extraBody: { max_tokens: 12, max_completion_tokens: 30 },
      sentSliders: ALL_SLIDERS_SENT,
      provider: PROVIDER_TYPE.CUSTOM,
      expected: ["max_tokens", "max_completion_tokens"],
    },
    {
      name: "Ollama gets max_completion_tokens as max_tokens",
      extraBody: { max_completion_tokens: 12 },
      sentSliders: ALL_SLIDERS_SENT,
      provider: PROVIDER_TYPE.OLLAMA,
      expected: ["max_completion_tokens (as max_tokens)"],
    },
    {
      name: "Ollama keeps max_tokens when both names are there",
      extraBody: { max_tokens: 12, max_completion_tokens: 30 },
      sentSliders: ALL_SLIDERS_SENT,
      provider: PROVIDER_TYPE.OLLAMA,
      expected: ["max_tokens"],
    },
    {
      name: "Bedrock gets max_tokens as max_completion_tokens",
      extraBody: { max_tokens: 40 },
      sentSliders: ALL_SLIDERS_SENT,
      provider: PROVIDER_TYPE.BEDROCK,
      expected: ["max_tokens (as max_completion_tokens)"],
    },
    {
      name: "Bedrock keeps max_completion_tokens when both names are there",
      extraBody: { max_tokens: 40, max_completion_tokens: 30 },
      sentSliders: ALL_SLIDERS_SENT,
      provider: PROVIDER_TYPE.BEDROCK,
      expected: ["max_completion_tokens"],
    },
  ])("$name", ({ extraBody, sentSliders, provider, expected }) => {
    const { sliderKeysInExtraBody } = getExtraBodySliderOverlap({
      extraBody,
      sentSliders,
      provider,
      exclusiveSampling: false,
    });

    expect(sliderKeysInExtraBody).toEqual(expected);
  });

  it.each([
    {
      name: "temperature sent, extra body top_p",
      sentSliders: { temperature: true },
      extraBody: { top_p: 0.5 },
      exclusiveSampling: true,
      expected: ["temperature", "top_p"],
    },
    {
      name: "top_p sent, extra body temperature",
      sentSliders: { top_p: true },
      extraBody: { temperature: 0.5 },
      exclusiveSampling: true,
      expected: ["top_p", "temperature"],
    },
    {
      name: "top_p sent, extra body top_p",
      sentSliders: { top_p: true },
      extraBody: { top_p: 0.5 },
      exclusiveSampling: true,
      expected: [undefined, undefined],
    },
    {
      name: "independent sampling",
      sentSliders: { temperature: true },
      extraBody: { top_p: 0.5 },
      exclusiveSampling: false,
      expected: [undefined, undefined],
    },
  ])(
    "names the slider key and the extra body key sent next to it: $name",
    ({ sentSliders, extraBody, exclusiveSampling, expected }) => {
      const { sliderKeyNextToExtraBody, extraBodyKeyNextToSlider } =
        getExtraBodySliderOverlap({
          extraBody,
          sentSliders,
          exclusiveSampling,
        });

      expect([sliderKeyNextToExtraBody, extraBodyKeyNextToSlider]).toEqual(
        expected,
      );
    },
  );
});
