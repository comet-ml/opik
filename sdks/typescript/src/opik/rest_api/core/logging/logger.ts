export const LogLevel = {
    Debug: "debug",
    Info: "info",
    Warn: "warn",
    Error: "error",
} as const;
export type LogLevel = (typeof LogLevel)[keyof typeof LogLevel];
const logLevelMap: Record<LogLevel, number> = {
    [LogLevel.Debug]: 1,
    [LogLevel.Info]: 2,
    [LogLevel.Warn]: 3,
    [LogLevel.Error]: 4,
};

export interface ILogger {
    /**
     * Logs a debug message.
     * @param message - The message to log
     * @param args - Additional arguments to log
     */
    debug(message: string, ...args: unknown[]): void;
    /**
     * Logs an info message.
     * @param message - The message to log
     * @param args - Additional arguments to log
     */
    info(message: string, ...args: unknown[]): void;
    /**
     * Logs a warning message.
     * @param message - The message to log
     * @param args - Additional arguments to log
     */
    warn(message: string, ...args: unknown[]): void;
    /**
     * Logs an error message.
     * @param message - The message to log
     * @param args - Additional arguments to log
     */
    error(message: string, ...args: unknown[]): void;
}

/**
 * Configuration for logger initialization.
 */
export interface LogConfig {
    /**
     * Minimum log level to output.
     * @default LogLevel.Info
     */
    level?: LogLevel;
    /**
     * Logger implementation to use.
     * @default new ConsoleLogger()
     */
    logger?: ILogger;
    /**
     * Whether logging should be silenced.
     * @default true
     */
    silent?: boolean;
}

/**
 * Default console-based logger implementation.
 */
export class ConsoleLogger implements ILogger {
    debug(message: string, ...args: unknown[]): void {
        console.debug(message, ...args);
    }
    info(message: string, ...args: unknown[]): void {
        console.info(message, ...args);
    }
    warn(message: string, ...args: unknown[]): void {
        console.warn(message, ...args);
    }
    error(message: string, ...args: unknown[]): void {
        console.error(message, ...args);
    }
}

/**
 * Logger class that provides level-based logging functionality.
 */
export class Logger {
    private readonly level: number;
    private readonly logger: ILogger;
    private readonly silent: boolean;

    /**
     * Creates a new logger instance.
     * @param config - Logger configuration
     */
    constructor(config: Required<LogConfig>) {
        this.level = logLevelMap[config.level];
        this.logger = config.logger;
        this.silent = config.silent;
    }

    /**
     * Checks if a log level should be output based on configuration.
     * @param level - The log level to check
     * @returns True if the level should be logged
     */
    public shouldLog(level: LogLevel): boolean {
        return !this.silent && this.level <= logLevelMap[level];
    }

    /**
     * Checks if debug logging is enabled.
     * @returns True if debug logs should be output
     */
    public isDebug(): boolean {
        return this.shouldLog(LogLevel.Debug);
    }

    /**
     * Logs a debug message if debug logging is enabled.
     * @param message - The message to log
     * @param args - Additional arguments to log
     */
    public debug(message: string, ...args: unknown[]): void {
        if (this.isDebug()) {
            this.logger.debug(message, ...args);
        }
    }

    /**
     * Checks if info logging is enabled.
     * @returns True if info logs should be output
     */
    public isInfo(): boolean {
        return this.shouldLog(LogLevel.Info);
    }

    /**
     * Logs an info message if info logging is enabled.
     * @param message - The message to log
     * @param args - Additional arguments to log
     */
    public info(message: string, ...args: unknown[]): void {
        if (this.isInfo()) {
            this.logger.info(message, ...args);
        }
    }

    /**
     * Checks if warning logging is enabled.
     * @returns True if warning logs should be output
     */
    public isWarn(): boolean {
        return this.shouldLog(LogLevel.Warn);
    }

    /**
     * Logs a warning message if warning logging is enabled.
     * @param message - The message to log
     * @param args - Additional arguments to log
     */
    public warn(message: string, ...args: unknown[]): void {
        if (this.isWarn()) {
            this.logger.warn(message, ...args);
        }
    }

    /**
     * Checks if error logging is enabled.
     * @returns True if error logs should be output
     */
    public isError(): boolean {
        return this.shouldLog(LogLevel.Error);
    }

    /**
     * Logs an error message if error logging is enabled.
     * @param message - The message to log
     * @param args - Additional arguments to log
     */
    public error(message: string, ...args: unknown[]): void {
        if (this.isError()) {
            this.logger.error(message, ...args);
        }
    }
}

export function createLogger(config?: LogConfig | Logger): Logger {
    if (config == null) {
        return defaultLogger;
    }
    if (config instanceof Logger) {
        return config;
    }
    config = config ?? {};
    config.level ??= LogLevel.Info;
    config.logger ??= new ConsoleLogger();
    config.silent ??= true;
    return new Logger(config as Required<LogConfig>);
}

const defaultLogger: Logger = new Logger({
    level: LogLevel.Info,
    logger: new ConsoleLogger(),
    silent: true,
});
