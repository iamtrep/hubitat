// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

// Target driver for apps/tests/test-singlethreaded.sh: every entry point a
// device has (command from an app, command over HTTP, scheduled handler,
// async callback, parse(), a child device calling the parent) runs work() and
// records its span on the hub clock. The test pushes a second copy with
// singleThreaded: true; keep the name and singleThreaded keys on their own lines.

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

metadata {
    definition(
        name: "Single Threaded Probe Driver",
        namespace: "tests",
        author: "PJ",
        singleThreaded: false
    ) {
        capability "Actuator"
        command "work", [[name: "tag", type: "STRING"], [name: "ms", type: "NUMBER"], [name: "via", type: "STRING"]]
        command "arm", [[name: "kind", type: "STRING"], [name: "tag", type: "STRING"], [name: "ms", type: "NUMBER"],
                        [name: "at", type: "NUMBER"], [name: "slowUri", type: "STRING"]]
        command "resetSpans"
        command "dumpSpans"
        command "makeChild"
        command "setMode", [[name: "mode", type: "STRING"], [name: "slowBase", type: "STRING"]]
    }
}

@Field static final ConcurrentHashMap<String, Map> SPANS = new ConcurrentHashMap<String, Map>()
@Field static final ConcurrentHashMap<String, Map> MODE = new ConcurrentHashMap<String, Map>()

void installed() { }
void updated() { }

// How work() holds the device: pauseExecution, a busy loop, or a blocking HTTP call.
void work(String tag, BigDecimal ms, String via) { doWork(tag, ms, via, null) }

private void doWork(String tag, BigDecimal ms, String via, Long callerAt) {
    long start = now()
    Map mode = MODE.get(device.id.toString()) ?: [m: "pause"]
    hold(mode, start + ((ms ?: 0) as long))
    SPANS.put("${device.id}:${tag}".toString(), [tag: tag, via: via ?: "command", start: start, end: now(), mode: mode.m, callerAt: callerAt])
}

private void hold(Map mode, long until) {
    if (mode.m == "busy") {
        long spins = 0
        while (now() < until) spins++
    } else if (mode.m == "http") {
        httpGet([uri: "${mode.slowBase}&until=${until}".toString(), timeout: 60]) { resp -> }
    } else {
        long wait = until - now()
        if (wait > 0) pauseExecution(wait)
    }
}

void setMode(String mode, String slowBase) { MODE.put(device.id.toString(), [m: mode ?: "pause", slowBase: slowBase]) }

void arm(String kind, String tag, BigDecimal ms, BigDecimal at, String slowUri) {
    if (kind == "scheduled") runInMillis(Math.max(1L, (at as long) - now()), "schedWork", [data: [tag: tag, ms: ms as int], overwrite: false])
    if (kind == "callback") asynchttpGet("cbWork", [uri: slowUri, timeout: 60], [tag: tag, ms: ms as int])
}

void schedWork(Map data) { work(data.tag as String, data.ms as BigDecimal, "scheduled") }
void cbWork(resp, Map data) { work(data.tag as String, data.ms as BigDecimal, "callback") }

void parse(String description) {
    List<String> p = (parseLanMessage(description)?.body ?: "").tokenize("|")
    if (p.size() == 2) work(p[0], p[1] as BigDecimal, "parse")
}

// Called by the child device.
void childWork(String tag, Integer ms, String via, Long callerAt = null) { doWork(tag, ms as BigDecimal, via, callerAt) }

void resetSpans() {
    String prefix = "${device.id}:".toString()
    SPANS.keySet().findAll { String k -> k.startsWith(prefix) }.each { SPANS.remove(it) }
}

void dumpSpans() {
    String prefix = "${device.id}:".toString()
    device.updateDataValue("spans", groovy.json.JsonOutput.toJson(SPANS.findAll { k, v -> k.startsWith(prefix) }.values() as List))
}

// Two children, so the two calls of a pair never share one device's command queue.
void makeChild() {
    ["child", "child2"].each { String s ->
        String dni = "${device.deviceNetworkId}-${s}".toString()
        if (!getChildDevice(dni)) addChildDevice("tests", "Single Threaded Probe Child", dni, [name: "${device.name}-${s}".toString(), isComponent: true])
    }
}
