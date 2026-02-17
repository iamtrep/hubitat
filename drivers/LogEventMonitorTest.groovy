/**
 * Log Event Monitor Test Driver
 * Emits log messages at specified levels to exercise LogEventMonitor filtering
 */

metadata {
    definition(
        name: "Log Event Monitor Test",
        namespace: "iamtrep",
        author: "pj"
    ) {
        capability "Actuator"

        command "emitLog", [
            [name: "message", type: "STRING", description: "Log message text"],
            [name: "level", type: "ENUM", constraints: ["trace", "debug", "info", "warn", "error"], description: "Log level"]
        ]
    }
}

void emitLog(String message, String level) {
    switch (level) {
        case "trace": log.trace message; break
        case "debug": log.debug message; break
        case "info":  log.info message; break
        case "warn":  log.warn message; break
        case "error": log.error message; break
        default:      log.info message; break
    }
}
