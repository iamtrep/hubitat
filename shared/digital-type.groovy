// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Shared block: the event `type` of a device report, "digital" when it is the result of a
// command this driver sent and "physical" for anything else. No protocol says which; the
// driver infers it (docs/hubitat-internal-apis.md, eventsJson).
//
// A command records the value it asked for. A report of that value before the deadline is
// digital and clears the record, changed or not, so the read-back of a command that changed
// nothing clears it too. A report of any other value is physical and leaves the record: a
// routine report that crosses the command, or someone undoing the command at the device.
// A record with no value (a fade whose steps never match the target) makes every report of
// the attribute digital until the deadline, and is not cleared by a report.
//
// The host file defines the store, shared by every device on the driver and safe across
// threads, which `state` is not (a driver's state is written back at method exit):
//   @Field static final ConcurrentHashMap<String, Map> DIGITAL_EXPECTED = new ConcurrentHashMap<>()
// See shared/README.md.
// block-version: 1
// requires: DIGITAL_EXPECTED

// Call before sending the command. `value` is what the attribute should become (null: any
// value until the deadline). `ep` keeps endpoints of a multi-endpoint device apart.
void expectDigital(String attr, Object value, long windowMs = 5000L, String ep = null) {
    DIGITAL_EXPECTED.put(digitalKey(attr, ep), [value: value?.toString(), until: now() + windowMs])
}

// The type for a report of `attr` = `value`. Values compare as text.
String reportType(String attr, Object value, String ep = null) {
    String key = digitalKey(attr, ep)
    Map e = DIGITAL_EXPECTED.get(key)
    if (e == null) return "physical"
    if (now() >= (e.until as long)) {
        DIGITAL_EXPECTED.remove(key, e)
        return "physical"
    }
    if (e.value == null) return "digital"
    if (e.value != value?.toString()) return "physical"
    DIGITAL_EXPECTED.remove(key, e)
    return "digital"
}

// Drops this device's records, for installed() and updated().
void clearDigital() {
    String prefix = "${device.id}/"
    DIGITAL_EXPECTED.keySet().findAll { String k -> k.startsWith(prefix) }.each { DIGITAL_EXPECTED.remove(it) }
}

String digitalKey(String attr, String ep) {
    return ep ? "${device.id}/${ep}/${attr}" : "${device.id}/${attr}"
}
