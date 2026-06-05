// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Zigbee Introspect — reflective dump of Hubitat's `zigbee` helper.

 For just the runtime class name of an object, use the platform-injected
 `getObjectClassName(value)` — this driver only goes deeper because it needs the
 actual Class object to walk methods/fields/type chains. Sandbox blocks
 `obj.getClass()` (MethodCallExpression), `java.lang.Class` as an import or type
 annotation, `Class.forName`, `java.lang.reflect.Modifier`, and `ArrayDeque`;
 `obj.class` (PropertyExpression) returns the Class object fine, and Class/Method/Field
 getter chains work from there. Output consumed by docs/hubitat-zigbee-helper.md.

 Usage: install on any virtual device, click `dumpAll`, then `hub file download
 zigbee_introspect.md <local>`.
*/

metadata {
    definition(name: "Zigbee Introspect", namespace: "iamtrep", author: "PJ") {
        capability "Actuator"
        command "dumpAll"
    }
    preferences {
        input name: "outFile", type: "STRING", title: "Output file name (File Manager)",
              defaultValue: "zigbee_introspect.md"
        input name: "maxClasses", type: "NUMBER", title: "Max classes to walk", defaultValue: 80
    }
}

def installed() { log.info "Zigbee Introspect installed" }
def updated()   { log.info "Zigbee Introspect updated" }

// ---- reflection helpers ----

private boolean isHubitatType(c) {
    def n = c?.getName()
    return n && (n.startsWith('hubitat.') || n.startsWith('com.hubitat.') ||
                 n.startsWith('physicalgraph.') || n.startsWith('smartthings.'))
}

private String safeSimple(c) {
    try { return c.getSimpleName() } catch (e) { return c.getName() }
}

private void appendClass(StringBuilder sb, cls, String header = null) {
    sb.append("\n## ${header ?: cls.getName()}\n\n")
    sb.append("- Class: `${cls.getName()}`\n")
    try { sb.append("- Superclass: `${cls.getSuperclass()?.getName() ?: '(none)'}`\n") } catch (e) {}
    try {
        def ifs = cls.getInterfaces()*.getName()
        if (ifs) sb.append("- Interfaces: ${ifs.collect{'`'+it+'`'}.join(', ')}\n")
    } catch (e) {}

    sb.append("\n### Methods\n\n")
    def seen = new HashSet()
    try {
        def methods = cls.getMethods().toList().sort { a, b ->
            (a.name <=> b.name) ?: (a.parameterCount <=> b.parameterCount)
        }
        methods.each { m ->
            if (m.declaringClass == Object.class) return
            def params = m.parameterTypes.collect { safeSimple(it) }.join(', ')
            def sig = "${safeSimple(m.returnType)} ${m.name}(${params})"
            if (seen.add(sig)) {
                def from = ""
                if (m.declaringClass != cls && isHubitatType(m.declaringClass)) {
                    from = " *(from ${safeSimple(m.declaringClass)})*"
                }
                sb.append("- `${sig}`${from}\n")
            }
        }
    } catch (e) {
        sb.append("(getMethods failed: ${e.message})\n")
    }

    try {
        def fields = cls.getFields()
        if (fields) {
            sb.append("\n### Fields\n\n")
            fields.sort { it.name }.each { f ->
                def line = "- `${safeSimple(f.type)} ${f.name}`"
                try {
                    // 0x08 = STATIC bit in java.lang.reflect.Modifier
                    if ((f.modifiers & 0x08) != 0) {
                        def val = f.get(null)
                        if (val != null) line += " = `${val}`"
                    }
                } catch (e) {}
                sb.append("${line}\n")
            }
        }
    } catch (e) {
        sb.append("(getFields failed: ${e.message})\n")
    }
}

private void collectReferenced(c, Set visited, List toVisit) {
    try {
        c.getMethods().each { m ->
            if (m.declaringClass == Object.class) return
            ([m.returnType] + m.parameterTypes.toList()).each { t ->
                def root = t
                while (root.isArray()) root = root.componentType
                if (root.isPrimitive() || !isHubitatType(root)) return
                if (visited.contains(root)) return
                visited << root
                toVisit.add(root)
            }
        }
    } catch (e) {}
}

// ---- main entry point ----

def dumpAll() {
    StringBuilder sb = new StringBuilder()
    def hub = location?.hubs?.first()
    sb.append("# Hubitat `zigbee` helper — reflective introspection\n\n")
    sb.append("Generated: `${new Date()}`  \n")
    sb.append("Hub firmware: `${hub?.firmwareVersionString ?: '?'}`  \n")
    sb.append("Source: driver `Zigbee Introspect` (iamtrep namespace), Groovy reflection on the `zigbee` object injected into drivers.\n")

    def zigbeeClass = zigbee.class
    def visited = new HashSet()
    visited << zigbeeClass
    def queue = []

    // Groovy bean-property snapshot (the one-liner DeviceInspector.groovy uses).
    // Complements the full class walk below: bean-properties show LIVE values for
    // readable getters but skip parameterized methods and static fields.
    sb.append("\n## `zigbee.properties` (bean-property snapshot)\n\n")
    sb.append("Per-instance readable getters via Groovy meta-bean accessor. ")
    sb.append("For just-the-name use of any object: `getObjectClassName(obj)`.\n\n")
    try {
        def props = [:]
        zigbee.properties.each { k, v -> props[k.toString()] = (v == null ? "null" : v.toString()) }
        props.sort().each { k, v ->
            def vs = v.length() > 120 ? (v.substring(0, 117) + "...") : v
            sb.append("- `${k}` = `${vs}`\n")
        }
        sb.append("\n_(${props.size()} bean properties)_\n")
    } catch (Throwable t) {
        sb.append("zigbee.properties blocked: ${t.message}\n")
    }

    appendClass(sb, zigbeeClass, "`zigbee` (`${zigbeeClass.getName()}`)")
    collectReferenced(zigbeeClass, visited, queue)

    int cap = (maxClasses as Integer) ?: 80
    int n = 1
    while (!queue.isEmpty() && n < cap) {
        def c = queue.remove(0)
        appendClass(sb, c)
        collectReferenced(c, visited, queue)
        n++
    }

    // ZoneStatus via the parsing helper (Class.forName is sandbox-blocked, so we must
    // get class objects from real instances). parseZoneStatus accepts a hex-string per
    // SmartThings docs: "zone status {number}".
    try {
        def zs = zigbee.parseZoneStatus("zone status 0x0000")
        def zsClass = zs.class
        if (visited.add(zsClass)) {
            appendClass(sb, zsClass, "ZoneStatus (via `zigbee.parseZoneStatus('zone status 0x0000')`)")
            collectReferenced(zsClass, visited, queue)
            n++
        }
    } catch (Throwable t) {
        sb.append("\n_zigbee.parseZoneStatus failed: ${t.message}_\n")
    }

    // Continue draining anything new discovered from the ZoneStatus walk
    while (!queue.isEmpty() && n < cap) {
        def c = queue.remove(0)
        appendClass(sb, c)
        collectReferenced(c, visited, queue)
        n++
    }

    sb.append("\n---\n\n_${n} classes inspected (cap ${cap})._\n")

    String content = sb.toString()
    String fname = (outFile as String) ?: "zigbee_introspect.md"
    try {
        uploadHubFile(fname, content.getBytes("UTF-8"))
        log.info "Wrote ${fname} to File Manager (${content.length()} chars, ${n} classes)"
    } catch (Exception e) {
        log.error "uploadHubFile failed: ${e.message} — dumping to log instead"
        content.split('\n').each { log.info it }
    }
}
