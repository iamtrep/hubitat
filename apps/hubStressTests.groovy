definition(
    name: "Hub Stress Test App",
    namespace: "iamtrep",
    author: "iamtrep",
    description: "Some HE stress test functions",
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

    //fileManagerStressTest()
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


//////////////////////////////////////////////

def fileManagerStressTest() {
    def fileList
    def timeStart = now()
    for(int i = 0;i<iterations;i++) {
        fileList = getHubFiles()      // List<Map<String,String>> getHubFiles(String folder = "")
    }
    def timeStop = now()
    log.debug("File List API call took ${(timeStop-timeStart)/iterations} ms on average")

    printFileList(fileList)

    def buffer
    timesStart = now()
    for(int i = 0;i<iterations;i++) {
       buffer = downloadHubFile(fileList[0]["name"])
    }
    timeStop = now()
    log.debug("File download API took ${(timeStop-timeStart)/iterations} ms on average")

    def fileList2
    timesStart = now()
    for(int i = 0;i<iterations;i++) {
       fileList2 = listFiles()
    }
    timeStop = now()
    log.debug("File list HTTP took ${(timeStop-timeStart)/iterations} ms on average")

    def buffer2
    timesStart = now()
    for(int i = 0;i<iterations;i++) {
       buffer2 = readFile(fileList[0]["name"])
    }
    timeStop = now()
    log.debug("File HTTP download took ${(timeStop-timeStart)/iterations} ms on average")
}

def printFileList(fileList) {
    fileList.each {
        // [date:1699675111000, size:266562, name:RGZW1791U_DB1-US_V1.05.01.otz, type:file]
        if (it["type"] == "file") {
            def formattedDate = new Date(it["date"].toLong())
            log.debug("File name : ${it["name"]}, Timestamp : ${formattedDate.getDateTimeString()}, Size: ${it["size"]}")
        }
    }
}


// From https://raw.githubusercontent.com/thebearmay/hubitat/main/libraries/templateProcessing.groovy

@SuppressWarnings('unused')
String readFile(fName){
    if(security) cookie = getCookie()
    uri = "http://${location.hub.localIP}:8080/local/${fName}"

    def params = [
        uri: uri,
        contentType: "text/html",
        textParser: true,
        headers: [
				"Cookie": cookie,
                "Accept": "application/octet-stream"
            ]
    ]

    try {
        httpGet(params) { resp ->
            if(resp!= null) {
               int i = 0
               String delim = ""
               i = resp.data.read()
               while (i != -1){
                   char c = (char) i
                   delim+=c
                   i = resp.data.read()
               }
               if(debugEnabled) log.info "File Read Data: $delim"
               return delim
            }
            else {
                log.error "Null Response"
            }
        }
    } catch (exception) {
        log.error "Read Error: ${exception.message}"
        return null;
    }
}

@SuppressWarnings('unused')
List<String> listFiles(filt = null){
    if(security) cookie = getCookie()
    if(debugEnabled) log.debug "Getting list of files"
    uri = "http://${location.hub.localIP}:8080/hub/fileManager/json";
    def params = [
        uri: uri,
        headers: [
				"Cookie": cookie
            ]
    ]
    try {
        fileList = []
        httpGet(params) { resp ->
            if (resp != null){
                if(logEnable) log.debug "Found the files"
                def json = resp.data
                for (rec in json.files) {
                    if(filt != null){
                        if(rec.name.contains("$filt")){
                            fileList << rec.name
                        }
                    } else
                        fileList << rec.name
                }
            } else {
                //
            }
        }
        if(debugEnabled) log.debug fileList.sort()
        return fileList.sort()
    } catch (e) {
        log.error e
    }
}

@SuppressWarnings('unused')
String getCookie(){
    try{
  	  httpPost(
		[
		uri: "http://127.0.0.1:8080",
		path: "/login",
		query: [ loginRedirect: "/" ],
		body: [
			username: username,
			password: password,
			submit: "Login"
			]
		]
	  ) { resp ->
		cookie = ((List)((String)resp?.headers?.'Set-Cookie')?.split(';'))?.getAt(0)
        if(debugEnable)
            log.debug "$cookie"
	  }
    } catch (e){
        cookie = ""
    }
    return "$cookie"

}
