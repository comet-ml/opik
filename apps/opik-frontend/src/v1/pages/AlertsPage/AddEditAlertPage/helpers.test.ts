import { ALERT_TYPE } from "@/types/alerts";
import { applyFieldReplacements } from "./helpers";

describe("applyFieldReplacements", () => {
  it("keeps the server-side title prefix in the Feishu preview", () => {
    const example = {
      card: { header: { title: { content: "Opik Alert: Example" } } },
    };

    const result = applyFieldReplacements(
      example,
      { name: "Production errors" },
      ALERT_TYPE.feishu,
    );

    expect(result).toEqual({
      card: {
        header: { title: { content: "Opik Alert: Production errors" } },
      },
    });
    expect(example.card.header.title.content).toBe("Opik Alert: Example");
  });
});
