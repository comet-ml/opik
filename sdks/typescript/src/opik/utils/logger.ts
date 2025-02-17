import { Logger } from "tslog";

const logLevels = {
  SILLY: 0,
  TRACE: 1,
  DEBUG: 2,
  INFO: 3,
  WARN: 4,
  ERROR: 5,
  FATAL: 6,
} as const;

const logger = new Logger({ name: "opik" });

const setLoggerLevel = (level: keyof typeof logLevels) => {
  logger.settings.minLevel = logLevels[level];
};

const disableLogger = () => {
  logger.settings.minLevel = 100;
};

setLoggerLevel(
  (process.env.OPIK_LOG_LEVEL as keyof typeof logLevels) || "INFO"
);

export { logger, disableLogger, setLoggerLevel };
