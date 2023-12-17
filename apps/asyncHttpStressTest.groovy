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
            input name: "iterations", type: "number", title: "Number of iterations to run", defaultValue: 30, required: true
            input name: "pacing", type: "number", title: "Number of milliseconds between iterations", defaultValue: 500, required: true
            input name: "httpTimeout", type: "number", title: "HTTP timeout in seconds", defaultValue: 30, required: true
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

def httpStressTest() {
    // overlapped async http get
    def timeStart = now()
    for(int i = 0;i<iterations;i++) {
        apiGet(i, iterations)
        pauseExecution(pacing)
    }
    def timeStop = now()
    log.debug("Initiated $iterations async HTTP GET calls in ${(timeStop-timeStart)} ms")
}

def apiGet(i, n) {
    Map requestParams =
	[
        uri: "https://httpstat.us/200?sleep=60000",
        headers: [
            requestContentType: 'application/json',
		    contentType: 'application/json',
            timeout: httpTimeout
        ]
	]

    asynchttpGet("getApi", requestParams, [iteration: i, total: n, timestamp: now()])
}

def getApi(resp, data){
    try {
        log.debug "$resp.properties - (${data.iteration+1}/$data.total ${(now()-data.timestamp)/1000}) - ${resp.getStatus()}"
        if (data.iteration + 1 == data.total) {
            // start a new batch
            httpStressTest()
        }
    } catch (Exception e) {
        log.error "getApi - $e.message"
    }
}
