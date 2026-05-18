// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

definition(
    name: "Async UDP Stress Test App",
    namespace: "iamtrep",
    author: "iamtrep",
    description: "Async UDP Stress Test App",
    menu: "Apps", // new in platform 2.5.0
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    //singleThreaded: true
)

import hubitat.helper.HexUtils

preferences {
    page(name: "mainPage", title: "App parameters", install: true, uninstall: true) {
        section("Call parameters") {
            input name: "iterations", type: "number", title: "Number of batches to run", defaultValue: 30, required: true
            input name: "numCalls", type: "number", title: "Number of calls per batch", defaultValue: 8, required: true
            input name: "pacing", type: "number", title: "Number of milliseconds between calls within a batch", defaultValue: 1, required: true
//            input name: "callTimeout", type: "number", title: "Call timeout in seconds", defaultValue: 30, required: true
            input name: "logThreshold", type: "number", title: "Logging threshold in ms", defaultValue: 250, required: true
            input name: "requestURL", type: "string", title: "Request IP and port (x.y.z.w:p)", defaultValue: "192.168.25.100:31337", required: true
            input name: "debugLogs", type: "bool", title: "Enable logging?", defaultValue: false, required: true
            input "stopButton", "button", title: "Stop current run"

        }
    }
}

void appButtonHandler(String btn) {
    switch(btn) {
        case "stopButton":
        default:
            unschedule()
            state.currentIteration = 0
            state.numCallsCompleted = 0
            state.numCallsOverThreshold = 0
            break
      }
}

void installed() {
    logDebug "installed()"
}

void updated() {
    log.info "updated() - starting a new test run"

    unschedule()
    state.currentIteration = 0
    state.numCallsCompleted = 0
    state.numCallsOverThreshold = 0
    appInstanceStats[getId()] = [:]
    //runIn(2,"udpStressTest",[misfire:"ignore"])
    schedule('*/2 * * ? * *',"udpStressTest",[misfire:"ignore"])
}

void uninstalled() {}

//////////////////////////////////////////////

import groovy.transform.Field
//@Field static Long currentIteration = 0
//@Field static Long numCallsCompleted = 0
//@Field static Long numCallsOverThreshold = 0
@Field static Map appInstanceStats = [:]

void udpStressTest() {
    state.currentIteration++
    if (state.currentIteration > iterations) {
        unschedule()
        state.currentIteration = 0
        return
    }

    // overlapped UDP send
    long timeStart = now()
    for(int i = 0;i<numCalls;i++) {
        sendUdpMessage(i+1, numCalls as int)
        if (pacing) pauseExecution(pacing)
    }
    long timeStop = now()
    logDebug("Initiated $numCalls async UDP calls in ${(timeStop-timeStart)} ms (iteration $state.currentIteration)")
    //log.info("Initiated $numCalls async UDP calls in ${(timeStop-timeStart)} ms (iteration $state.currentIteration)")
}

void sendUdpMessage(int i, int n) {
    long timeSent = now()
    String payload = "$state.currentIteration,$i,$n,$timeSent"
	hubitat.device.HubAction myHubAction = new hubitat.device.HubAction(
        payload,
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "$requestURL",
		 //timeout: callTimeout,
         parseWarning: true,
		 ignoreResponse: false,
		 callback: "parseUdpMessage"])
    try {
		sendHubCommand(myHubAction)
        logDebug("sendUdpMessage: $payload")
	} catch (e) {
		log.warn("sendUdpMessage error = ${e}")
	}
}

void parseUdpMessage(message){
    try {
        BigInteger timeReceived = now() as BigInteger
        Map resp = parseLanMessage(message.description)
        String[] params = resp.payload.split('2C') // payload is ASCII representation as String
        BigInteger batch = parseBigInteger(hubitat.helper.HexUtils.hexStringToByteArray(params[0]))
        BigInteger iterationNumber = parseBigInteger(hubitat.helper.HexUtils.hexStringToByteArray(params[1]))
        BigInteger callCount = parseBigInteger(hubitat.helper.HexUtils.hexStringToByteArray(params[2]))
        BigInteger timeSent = parseBigInteger(hubitat.helper.HexUtils.hexStringToByteArray(params[3]))

        // Echo server adds its own timestamp on the return, but time on client & server not synchronized well enough to make this useful
        //BigInteger timeEchoed = parseBigInteger(hubitat.helper.HexUtils.hexStringToByteArray(params[4]))
        //BigInteger sendTime = timeEchoed-timeSent
        //BigInteger echoTime = timeReceived-timeEchoed

        BigInteger roundTripTime = timeReceived-timeSent
        logDebug("batch $batch iteration $iterationNumber of $callCount - round-trip time $roundTripTime ms")
        state.numCallsCompleted++

        if (roundTripTime > logThreshold) {
            state.numCallsOverThreshold++
            //log.warn("batch $batch iteration $iterationNumber of $callCount - send $sendTime ms / echo $echoTime ms / round-trip $roundTripTime ms ($numCallsOverThreshold/$numCallsCompleted)")
            log.warn("batch $batch iteration $iterationNumber of $callCount - round-trip $roundTripTime ms ($state.numCallsOverThreshold/$state.numCallsCompleted)")
        }

        if (iterationNumber == callCount) {
            logDebug("batch over")
            //runIn(2,"udpStressTest", [misfire:"ignore"])
        }
    }

    catch (Exception e) {
        log.error "parseUdpMessage - $e.message"
    }
}


BigInteger parseBigInteger(byte[] bytes)
{
    BigInteger sum = 0

    bytes?.eachWithIndex
    { it, idx ->
        sum += ((it - 0x30) * (Math.pow(10, (bytes.size() - 1) - idx)))
    }

    return sum
}

void logDebug(message)
{
    if(debugLogs) log.debug(message)
}
