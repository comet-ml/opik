import { OpikApiClient } from "@/rest_api";
import type { Span, Trace as ITrace } from "@/rest_api/api";

export class Trace {
  private spans: Span[] = [];

  constructor(
    private data: ITrace & { id: string },
    private apiClient: OpikApiClient
  ) {}

  public end = async () => {
    await this.update({ endTime: new Date() });
  };

  public span = async (spanData: Span) => {
    await this.apiClient.spans.createSpans({ spans: [spanData] }).asRaw();
    this.spans.push(spanData);
    return spanData;
  };

  public update = async (updates: {
    endTime?: Date;
    metadata?: Record<string, any>;
    input?: Record<string, any>;
    output?: Record<string, any>;
    tags?: string[];
  }) => {
    await client.traces.updateTrace(this.data.id, updates).asRaw();
    this.data = { ...this.data, ...updates };
  };
}
