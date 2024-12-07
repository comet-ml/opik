import { OpikApiClient } from "../src/opik/rest_api/Client";

describe("OpikApiClient", () => {
  let client: OpikApiClient;

  beforeAll(() => {
    client = new OpikApiClient({
      environment: "https://www.comet.com/opik/api",
    });
  });

  it("should fetch system usage", async () => {
    const traceCount = await client.systemUsage.getTracesCountForWorkspaces();
    expect(traceCount.workspacesTracesCount).toBe(1);
  });
});
