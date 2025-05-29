import { OpikError } from "../BaseError";
import { experimentErrorMessages } from "./errorMessages";

const experimentErrorCodes = {
  EXPERIMENT_NOT_FOUND: "EXPERIMENT_NOT_FOUND",
};

export class ExperimentNotFoundError extends OpikError {
  constructor(name: string) {
    super({
      message: experimentErrorMessages.EXPERIMENT_NOT_FOUND(name),
      code: experimentErrorCodes.EXPERIMENT_NOT_FOUND,
    });
  }
}
