/*
 MIT License

 Copyright (c) 2025 pj

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in all
 copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 SOFTWARE.

 Location Event Mapper - Main app

 For creating & grouping Location Event Mapper apps.  These apps set virtual contact sensor states based on location event triggers.

*/

definition(
    name: "Location Event Mapper",
    namespace: "iamtrep",
    author: "pj",
    singleInstance: true,
    description: "TBD",
    category: "Convenience",
    importUrl: "",
    iconUrl: "",
    iconX2Url: ""
)


import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String app_version = "0.0.1"


preferences {
    page(name: "mainPage")
}

Map mainPage(){
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section ("Set up or manage Sensor Aggregator instances"){
            app(name: "lemChildApps", appName: "Location Event Mapper Child", namespace: "iamtrep", title: "Create New Location Event Mapper", submitOnChange: true, multiple: true)
        }
    }
}

void installed() {
    initialize()
}

void updated() {
    unsubscribe()
    initialize()
}

void initialize() {
    log.debug "there are ${getChildApps().size()} location event mappers : ${getChildApps().collect { it.label } }"
}
