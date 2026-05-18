// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

definition(
    name: "File Manager API test",
    namespace: "Example",
    author: "Hubitat Example",
    description: "tbd",
    menu: "Apps", // new in platform 2.5.0
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "")

preferences {
    page(name: "mainPage", title: "App parameters", install: true, uninstall: true) {
        section("Call parameters") {
            input name: "numCalls", type: "number", title: "Number of calls", defaultValue: 30, required: true
            input "startButton", "button", title: "Start a new run", disabled: state.isRunning
        }
    }
}

void installed() {
    log.debug "installed()"
    state.isRunning = false
}

void updated() {
    log.debug "updated()"
}

void uninstalled() {
    log.debug "uninstalled()"
}


void appButtonHandler(String btn) {
    switch(btn) {
        case "startButton":
        default:
            if (state.isRunning == false) {
                runIn(1, "fileManagerTest")
            }
            break
    }
}


void fileManagerTest() {
    state.isRunning = true

    int iterations = numCalls as int

    List fileList
    long timeStart = now()
    for(int i = 0;i<iterations;i++) {
        fileList = getHubFiles()      // List<Map<String,String>> getHubFiles(String folder = "")
    }
    long timeStop = now()
    log.debug("File List API call took ${(timeStop-timeStart)/iterations} ms on average")

    printFileList(fileList)
    String downloadTestFile = fileList[0]["name"] as String
    log.debug("Using $downloadTestFile for download tests")

    byte[] buffer
    timesStart = now()
    for(int i = 0;i<iterations;i++) {
       buffer = downloadHubFile(downloadTestFile)
    }
    timeStop = now()
    log.debug("File download API took ${(timeStop-timeStart)/iterations} ms on average")

    List<String> fileList2
    timesStart = now()
    for(int i = 0;i<iterations;i++) {
       fileList2 = listFiles()
    }
    timeStop = now()
    log.debug("File list HTTP took ${(timeStop-timeStart)/iterations} ms on average")

    String buffer2
    timesStart = now()
    for(int i = 0;i<iterations;i++) {
       buffer2 = readFile(downloadTestFile)
    }
    timeStop = now()
    log.debug("File HTTP download took ${(timeStop-timeStart)/iterations} ms on average")

    state.isRunning = false
}


void printFileList(List fileList) {
    fileList.each {
        // [date:1699675111000, size:266562, name:RGZW1791U_DB1-US_V1.05.01.otz, type:file]
        if (it["type"] == "file") {
            Date formattedDate = new Date((it["date"] as String).toLong())
            log.debug("File name : ${it["name"]}, Timestamp : ${formattedDate.getDateTimeString()}, Size: ${it["size"]}")
        }
    }
}


// The following methods are taken from https://raw.githubusercontent.com/thebearmay/hubitat/main/libraries/templateProcessing.groovy

@SuppressWarnings('unused')
String readFile(String fName){
    if(security) cookie = getCookie()
    uri = "http://${location.hub.localIP}:8080/local/${fName}"

    Map params = [
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
List<String> listFiles(String filt = null){
    if(security) cookie = getCookie()
    if(debugEnabled) log.debug "Getting list of files"
    uri = "http://${location.hub.localIP}:8080/hub/fileManager/json";
    Map params = [
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
                Map json = resp.data as Map
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
