/**
 *  Generic WebSocket Test Driver
 */

metadata {
    definition(name: "Generic WebSocket Test", namespace: "iamtrep", author: "pj") {
        command "connect"
        command "disconnect"

        attribute "status", "string"
    }

    preferences {
        input name: "wsUrl", type: "string", title: "WebSocket URL", required: true
        input name: "logEnable", type: "bool", title: "Enable logging", defaultValue: true
    }
}

def connect() {
    if (!wsUrl) {
        log.error "No URL configured"
        return
    }
    log.info "Connecting to: ${wsUrl}"
    sendEvent(name: "status", value: "connecting")
    interfaces.webSocket.connect(wsUrl)
}

def disconnect() {
    log.info "Disconnecting"
    interfaces.webSocket.close()
    sendEvent(name: "status", value: "disconnected")
}

def webSocketStatus(String message) {
    log.info "webSocketStatus: ${message}"
    sendEvent(name: "status", value: message)
}

def parse(String message) {
    state.count = (state.count ?: 0) + 1

    if (message.contains('"clusterId":"0019"')) {
        state.otaCount = (state.otaCount ?: 0) + 1
        log.info "OTA #${state.otaCount}: ${message}"
    } else {
        if (logEnable) log.debug "msg #${state.count}: ${message}"
    }
}
