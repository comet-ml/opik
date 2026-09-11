import { Opik, OpikConfig } from "opik";

export class OpikSingleton {
  private static instance: Opik | null = null;

  public static getInstance(params?: Partial<OpikConfig>): Opik {
    if (!OpikSingleton.instance) {
      OpikSingleton.instance = new Opik(params);
    }
    return OpikSingleton.instance;
  }
}
