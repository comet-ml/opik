import { link } from "ansi-escapes";
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

export function createLink(url: string, text: string = url): string {
  return link(text, url);
}

export const logger = new Logger({
  hideLogPositionForProduction: true,
  prettyLogTemplate:
    "{{yyyy}}.{{mm}}.{{dd}} {{hh}}:{{MM}}:{{ss}}:{{ms}}\t{{logLevelName}}\t",
});

export const setLoggerLevel = (level: keyof typeof logLevels) => {
  logger.settings.minLevel = logLevels[level];
};

export const disableLogger = () => {
  logger.settings.minLevel = 100;
};

setLoggerLevel(
  (process.env.OPIK_LOG_LEVEL as keyof typeof logLevels) || "INFO"
);
