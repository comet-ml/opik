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

export class EnvironmentColorUpdateNotAllowedError extends EnvironmentError {
  constructor(name: string) {
    super(
      `Cannot change the colour of the built-in environment '${name}'. ` +
        "Colour updates are not allowed for 'production', 'staging', or 'development'."
    );
    this.name = "EnvironmentColorUpdateNotAllowedError";
  }
}
