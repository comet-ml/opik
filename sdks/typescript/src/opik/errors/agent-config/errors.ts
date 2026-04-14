import { OpikError } from "@/errors/BaseError";

export class ConfigNotFoundError extends OpikError {
  constructor(message: string) {
    super({ message, code: "CONFIG_NOT_FOUND" });
    this.name = "ConfigNotFoundError";
  }
}

export class ConfigMismatchError extends OpikError {
  constructor(message: string) {
    super({ message, code: "CONFIG_MISMATCH" });
    this.name = "ConfigMismatchError";
  }
}
