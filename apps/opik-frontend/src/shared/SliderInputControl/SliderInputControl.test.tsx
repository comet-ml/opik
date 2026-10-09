import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import SliderInputControl from "./SliderInputControl";

const renderControl = ({
  onChange = vi.fn(),
  defaultValue = 0,
  value = 0,
}: {
  onChange?: (v: number) => void;
  defaultValue?: number;
  value?: number;
} = {}) => {
  render(
    <SliderInputControl
      id="temperature"
      label="Temperature"
      min={0}
      max={1}
      step={0.01}
      defaultValue={defaultValue}
      value={value}
      onChange={onChange}
    />,
  );
  return { onChange, input: screen.getByTestId("temperature-input") };
};

describe("SliderInputControl typed value", () => {
  it.each(["Enter", "Tab", "Escape"])("saves the typed value on %s", (key) => {
    const { onChange, input } = renderControl();

    fireEvent.change(input, { target: { value: "0.7" } });
    fireEvent.keyDown(input, { key });

    expect(onChange).toHaveBeenCalledWith(0.7);
  });

  it("saves the typed value on blur", () => {
    const { onChange, input } = renderControl();

    fireEvent.change(input, { target: { value: "0.7" } });
    fireEvent.blur(input);

    expect(onChange).toHaveBeenCalledWith(0.7);
  });

  it("does not save while the user is still typing", () => {
    const { onChange, input } = renderControl();

    fireEvent.change(input, { target: { value: "0.7" } });
    fireEvent.keyDown(input, { key: "5" });

    expect(onChange).not.toHaveBeenCalled();
  });

  it.each(
    ["Enter", "Tab", "Escape"].flatMap((key) =>
      ["", "abc"].map((draft) => [key, draft]),
    ),
  )("goes back to the default on %s when the box holds %j", (key, draft) => {
    const { onChange, input } = renderControl({
      defaultValue: 0.5,
      value: 0.2,
    });

    fireEvent.change(input, { target: { value: draft } });
    fireEvent.keyDown(input, { key });

    expect(onChange).toHaveBeenCalledWith(0.5);
    expect(input).toHaveValue("0.5");
  });

  it("clamps a value above the max when Enter saves it", () => {
    const { onChange, input } = renderControl();

    fireEvent.change(input, { target: { value: "3" } });
    fireEvent.keyDown(input, { key: "Enter" });

    expect(onChange).toHaveBeenCalledWith(1);
    expect(input).toHaveValue("1");
  });
});
