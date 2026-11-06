import { useState } from "react";
import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import RuleConfig from "@/v2/pages-shared/guardrails/RuleConfig/RuleConfig";

const ControlledTopicsList = ({ initial }: { initial: string[] }) => {
  const [value, setValue] = useState(initial);
  return (
    <>
      <RuleConfig.TopicsList
        id="topics"
        label="Restricted topics"
        hint="Hint."
        value={value}
        onChange={setValue}
      />
      <output data-testid="value">{JSON.stringify(value)}</output>
    </>
  );
};

const input = () =>
  screen.getByLabelText(/Restricted topics/) as HTMLInputElement;
const formValue = () => JSON.parse(screen.getByTestId("value").textContent!);
const boxes = () =>
  screen
    .queryAllByRole("button", { name: /^Remove / })
    .map((button) => button.getAttribute("aria-label")!.replace("Remove ", ""));

describe("TopicsList", () => {
  it("boxes each topic followed by a comma and keeps the rest as the one being typed", () => {
    render(<ControlledTopicsList initial={[]} />);

    fireEvent.change(input(), { target: { value: "finance, weapons, mus" } });

    expect(boxes()).toEqual(["finance", "weapons"]);
    expect(input().value).toBe("mus");
    expect(formValue()).toEqual(["finance", "weapons", "mus"]);
  });

  it("shows stored topics as boxes", () => {
    render(<ControlledTopicsList initial={["finance", "weapons"]} />);

    expect(boxes()).toEqual(["finance", "weapons"]);
    expect(input().value).toBe("");
  });

  it("boxes the topic being typed on Enter", () => {
    render(<ControlledTopicsList initial={["finance"]} />);

    fireEvent.change(input(), { target: { value: "music " } });
    fireEvent.keyDown(input(), { key: "Enter" });

    expect(boxes()).toEqual(["finance", "music"]);
    expect(formValue()).toEqual(["finance", "music"]);
  });

  it("removes a box", () => {
    render(<ControlledTopicsList initial={["finance", "weapons"]} />);

    fireEvent.click(screen.getByRole("button", { name: "Remove finance" }));

    expect(formValue()).toEqual(["weapons"]);
  });

  it("reopens the last box on Backspace in an empty field", () => {
    render(<ControlledTopicsList initial={["finance", "weapons"]} />);

    fireEvent.keyDown(input(), { key: "Backspace" });

    expect(boxes()).toEqual(["finance"]);
    expect(input().value).toBe("weapons");
    expect(formValue()).toEqual(["finance", "weapons"]);
  });
});
