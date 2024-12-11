import { OpikApiClient } from "@opik";

describe("OpikApiClient", () => {
  let client: OpikApiClient;

  beforeAll(() => {
    client = new OpikApiClient({
      environment: "http:/localhost:5173/api",
    });
  });

  it("should fetch system usage", async () => {
    const projects = await client.projects.findProjects().asRaw();

    expect(projects.data.total).toBe(1);
  });
});
