definition(
    name: "Async HTTP Stress Test App",
    namespace: "iamtrep",
    author: "iamtrep",
    description: "Async HTTP Stress Test App",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    //singleThreaded: true
)

preferences {
    page(name: "mainPage", title: "App parameters", install: true, uninstall: true) {
        section("Call parameters") {
            input name: "iterations", type: "number", title: "Number of batches to run", defaultValue: 30, required: true
            input name: "numCalls", type: "number", title: "Number of calls per batch", defaultValue: 8, required: true
            input name: "pacing", type: "number", title: "Number of milliseconds between calls within a batch", defaultValue: 1, required: true
            input name: "httpTimeout", type: "number", title: "HTTP timeout in seconds", defaultValue: 30, required: true
            input name: "requestURL", type: "string", title: "Request URL", defaultValue: "https://httpstat.us/200?sleep=60000", required: true
        }
    }
}

def installed() {
    log.debug "installed()"
}

def updated() {
    log.debug "updated()"

    httpStressTest()
}

def uninstalled() {}

//////////////////////////////////////////////

import groovy.transform.Field
@Field static Long currentIteration = 0

def httpStressTest() {
    currentIteration++
    if (currentIteration >= iterations) {
        currentIteration = 0
        return
    }

    // overlapped async http get
    def timeStart = now()
    for(int i = 0;i<numCalls;i++) {
        apiGet(i, numCalls)
        pauseExecution(pacing)
    }
    def timeStop = now()
    log.debug("Initiated $numCalls async HTTP GET calls in ${(timeStop-timeStart)} ms (iteration $currentIteration)")
}

def apiGet(i, n) {
    Map requestParams =
	[
        uri: requestURL,
        requestContentType: 'application/json',
		contentType: 'application/json',
        headers: [:], // [ X-HttpStatus-Sleep: 60000 ],
        timeout: httpTimeout
	]

    asynchttpGet("getApi", requestParams, [call: i, total: n, timestamp: now()])
}

def getApi(resp, data){
    try {
        log.debug "$resp.properties - (${data.call+1}/$data.total ${(now()-data.timestamp)/1000}) ($currentIteration) - ${resp.getStatus()}"
        if (data.call + 1 == data.total) {
            // start a new batch
            runIn(1,"httpStressTest")
        }
    } catch (Exception e) {
        log.error "getApi - $e.message"
    }
}
