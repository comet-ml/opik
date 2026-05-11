export class EnvironmentError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "EnvironmentError";
  }
}

export class EnvironmentAlreadyExistsError extends EnvironmentError {
  constructor(name: string) {
    super(`Environment '${name}' already exists in this workspace.`);
    this.name = "EnvironmentAlreadyExistsError";
  }
}
