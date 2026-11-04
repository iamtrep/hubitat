// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Shared block: count a sensor as quiet from its last activity, with a limit learned per sensor.
// The caller keeps [last: ms, gaps: [ms…]] per sensor (in state), feeds it getLastActivity()
// times, and passes its own setting, floor and gap counts. See shared/README.md.
// block-version: 1

Map recordActivity(Map s, Long at, Integer keep) {
    Long last = (s?.last != null) ? (s.last as Long) : null
    List<Long> gaps = []
    for (Object g : (List) (s?.gaps ?: [])) gaps << (g as Long)
    if (at == null || (last != null && at <= last)) return [last: last, gaps: gaps]
    if (last != null) {
        gaps << (at - last)
        while (gaps.size() > keep) gaps.remove(0)
    }
    return [last: at, gaps: gaps]
}

// Twice the second-longest gap: one outage in the history does not stretch the limit.
// The setting and the floor are the minimum, while learning too: event-only sensors
// (contacts) learn short gaps from bursts.
Long quietLimitMs(List gaps, Long settingMs, Long floorMs, Integer minGaps) {
    Long least = Math.max(settingMs, floorMs)
    if (gaps == null || gaps.size() < minGaps) return least
    List<Long> sorted = []
    for (Object g : gaps) sorted << (g as Long)
    sorted.sort()
    return Math.max(least, 2L * sorted[sorted.size() - 2])
}

boolean isQuiet(Map s, Long now, Long limitMs) {
    if (s?.last == null) return true
    return now - (s.last as Long) > limitMs
}
