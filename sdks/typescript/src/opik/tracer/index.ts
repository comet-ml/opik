export interface Span {
  name: string;
  startTime: number;
  endTime?: number;
  data?: Record<string, any>;
}

export class Tracer {
  private spans: Span[] = [];

  public startSpan(name: string, data?: Record<string, any>): Span {
    const span: Span = { name, startTime: Date.now(), data };
    this.spans.push(span);
    return span;
  }

  public endSpan(span: Span) {
    span.endTime = Date.now();
  }

  public logTrace(name: string, data?: Record<string, any>) {
    const span = this.startSpan(name, data);
    this.endSpan(span);
  }

  public async logSpan<T>(
    name: string,
    fn: () => Promise<T>,
    data?: Record<string, any>
  ): Promise<T> {
    const span = this.startSpan(name, data);
    try {
      const result = await fn();
      this.endSpan(span);
      return result;
    } catch (err) {
      this.endSpan(span);
      throw err;
    }
  }
}
