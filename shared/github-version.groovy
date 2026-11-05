// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Shared block: the latest CODE_VERSION of this app on GitHub, for an "update available" note.
// IMPORT_URL_APP is the raw URL of the app's source; refreshUpdateLabel() is called when a
// new value arrives (it may do nothing). See shared/README.md.
// block-version: 1
// requires: IMPORT_URL_APP, refreshUpdateLabel, logNet

// Returns the last version seen on GitHub at once, and asks GitHub again at most once an hour.
// The hour counts from the request, so a failed request or a lost callback waits for the next
// hour instead of retrying on every call, and no in-flight flag can stay stuck.
String checkGithubVersion() {
    long last = (state.lastGithubVersionCheck ?: 0L) as long
    if (now() - last >= 3600000L) {
        state.lastGithubVersionCheck = now()
        asynchttpGet('githubVersionCallback', [uri: IMPORT_URL_APP, contentType: "text/plain", timeout: 10])
    }
    return state.lastGithubVersion as String
}

void githubVersionCallback(resp, data) {
    if (resp.hasError() || resp.status != 200) {
        logNet "GitHub version check failed: HTTP ${resp.status}"
        return
    }
    java.util.regex.Matcher m = ((resp.data?.toString() ?: '') =~ /CODE_VERSION\s*=\s*"([^"]+)"/)
    if (m.find()) {
        state.lastGithubVersion = m.group(1)
        refreshUpdateLabel()
    }
}
