# Logging emoji scheme

Every operational log line is prefixed with one emoji that encodes its
**category** — what the line is about — not its severity. Hubitat's log UI
already color-codes by level (info/warn/error), so an emoji for severity would
be redundant; the domain is what the UI gives no help with, and that is where a
scannable aggregate hub log is won.

One category emoji leads each categorized line, before the existing name prefix:

```
⬇️ Kitchen Contact: contact=open (zone 1)
```

Anomalies override the category: `logWarn`/`logError` carry a severity flag
instead of a category glyph.

## Palette

| Helper     | Emoji | Covers                                               | Level |
|------------|-------|------------------------------------------------------|-------|
| `logRx`    | ⬇️    | inbound: parse, received, zdo, read, reporting        | debug |
| `logCmd`   | ⬆️    | outbound: on, off, setpoint, sending, write, refresh  | info  |
| `logCfg`   | 🔧    | setup: installed, updated, configure, preferences     | info  |
| `logNet`   | 🌐    | cloud/IO: http, websocket, connect, token, authorize  | debug |
| `logSched` | ⏰    | scheduling, polling, timers                           | debug |
| `logOta`   | 📦    | ota, firmware (drivers only)                          | info  |
| `logVer`   | 🏷️    | driver/app version change or new-version notice       | info  |
| `logWarn`  | ⚠️    | unexpected / unhandled / invalid / filtered / degraded| warn  |
| `logError` | 🛑    | failure — HTTP failed, could-not, exception           | error |
| `logTrace` | 🔬    | raw firehose (gated)                                  | trace |

Direction mnemonic: data comes **down** from the device (`⬇️`), commands go
**up** to it (`⬆️`). `logInfo`/`logDebug` stay **emoji-less**, for lines with no
category — so categorized lines pop and uncategorized ones read as neutral.

One level per category (a documented simplification): a notable line in a
debug-level category uses plain `logInfo`/`logDebug` and forgoes the glyph,
rather than expanding the helper set.

## Driver block

Paste at the bottom of a driver, reusing its existing `debugEnable`/`traceEnable`
gates:

```groovy
// ── Logging ───────────────────────────────────────────────────────────
//   ⬇️ Rx  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  📦 Ota  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${device.displayName}: " }

void logRx   (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { log.info  logp('⬆️') + m }
void logCfg  (String m) { log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logOta  (String m) { log.info  logp('📦') + m }
void logVer  (String m) { log.info  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${device.displayName}: ${m}" }
```

## App block

Three differences: `app.getLabel()` prefix, `⬇️` is `logEvt` (subscription
events), no `logOta`.

```groovy
// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { log.info  logp('⬆️') + m }
void logCfg  (String m) { log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logVer  (String m) { log.info  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${app.getLabel()}: ${m}" }
```

Substitute the file's existing gate variables (`debugEnable`/`traceEnable`,
`enableDebug`, `logLevel`, …) — do not rename an existing gate. Keep every
helper even if the file emits none of that category. Leave `txtEnable`-gated
descriptionText event logging as `logInfo` (emoji-less): it is user-facing event
text, not operational scanning.
