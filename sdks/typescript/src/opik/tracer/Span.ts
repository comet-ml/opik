import { OpikApiClient } from "@/rest_api";
import type { Span as ISpan } from "@/rest_api/api";

export interface SavedSpan extends ISpan {
  id: string;
}

export class Span {
  constructor(
    private data: SavedSpan,
    private apiClient: OpikApiClient
  ) {}

  public end = async () => {
    await this.update({ endTime: new Date() });
  };

  public update = async (updates: {
    endTime?: Date;
    errorInfo?: {
      message: string;
      stacktrace?: string;
      type?: string;
    };
    input?: Record<string, any>;
    metadata?: Record<string, any>;
    model?: string;
    output?: Record<string, any>;
    provider?: string;
    tags?: string[];
    usage?: Record<string, number>;
  }) => {
    await this.apiClient.spans
      .updateSpan(this.data.id, { traceId: this.data.traceId, ...updates })
      .asRaw();
    this.data = { ...this.data, ...updates };
  };
}
