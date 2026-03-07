/*
 MIT License

 Copyright (c) 2026 pj

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

 Virtual Switch + PowerSource

 Virtual device that exposes both Switch and PowerSource capabilities with
 synchronized state. Designed for testing the Always-On Switch Monitor app's
 power outage detection via either capability.

 Sync logic:
   on()                      → switch=on,  powerSource=battery
   off()                     → switch=off, powerSource=mains
   setPowerSource("mains")   → switch=off, powerSource=mains
   setPowerSource(other)     → switch=on,  powerSource={value}
 */

metadata {
    definition(name: 'Virtual Switch + PowerSource', namespace: 'iamtrep', author: 'pj') {
        capability 'Switch'
        capability 'PowerSource'

        command 'setPowerSource', [[name: 'source', type: 'ENUM', constraints: ['mains', 'battery', 'dc', 'unknown'], description: 'Power source type']]
    }

    preferences {
        input name: 'txtEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
    }
}

void installed() {
    sendEvent(name: 'switch', value: 'off', descriptionText: 'Initialized to off')
    sendEvent(name: 'powerSource', value: 'mains', descriptionText: 'Initialized to mains')
}

void updated() {
}

void parse(String description) {
}

void on() {
    String descriptionText = "${device.displayName} was turned on"
    sendEvent(name: 'switch', value: 'on', descriptionText: descriptionText)
    sendEvent(name: 'powerSource', value: 'battery', descriptionText: "${device.displayName} power source set to battery")
    if (txtEnable) { log.info descriptionText }
}

void off() {
    String descriptionText = "${device.displayName} was turned off"
    sendEvent(name: 'switch', value: 'off', descriptionText: descriptionText)
    sendEvent(name: 'powerSource', value: 'mains', descriptionText: "${device.displayName} power source set to mains")
    if (txtEnable) { log.info descriptionText }
}

void setPowerSource(String source) {
    String descriptionText = "${device.displayName} power source set to ${source}"
    sendEvent(name: 'powerSource', value: source, descriptionText: descriptionText)
    String switchValue = (source == 'mains') ? 'off' : 'on'
    sendEvent(name: 'switch', value: switchValue, descriptionText: "${device.displayName} was turned ${switchValue}")
    if (txtEnable) { log.info descriptionText }
}
