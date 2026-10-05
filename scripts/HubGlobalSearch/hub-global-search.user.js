// ==UserScript==
// @name         Hubitat Global Search
// @namespace    iamtrep
// @version      0.1.0
// @description  One search box for devices, apps, rules, dashboards, rooms and code on a Hubitat hub. Ctrl+K or Cmd+K.
// @match        http://*/*
// @match        https://*/*
// @updateURL    https://raw.githubusercontent.com/iamtrep/hubitat/main/scripts/HubGlobalSearch/hub-global-search.user.js
// @downloadURL  https://raw.githubusercontent.com/iamtrep/hubitat/main/scripts/HubGlobalSearch/hub-global-search.user.js
// @grant        none
// @run-at       document-idle
// ==/UserScript==

// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

// Runs only on Hubitat admin pages, which all define globalCurrentVueUIPage. Reads the same JSON lists
// the hub UI pages use (firmware 2.5.2.129). Same-origin fetches, so hub security works once logged in.

(function () {
    'use strict'
    if (window.top !== window.self || typeof window.globalCurrentVueUIPage === 'undefined') return

    const CACHE_MS = 5 * 60000
    const MAX_PER_GROUP = 25
    const GROUP_ORDER = ['Device', 'Room', 'Dashboard', 'Automation', 'Integration', 'App', 'App code', 'Driver code', 'Library', 'Bundle']
    const STORE_KEY = 'hgs-index'

    const norm = s => String(s ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
    const stripHtml = s => String(s ?? '').replace(/<[^>]*>/g, '').trim()
    const getJson = url => fetch(url, { credentials: 'same-origin' }).then(r => r.ok ? r.json() : Promise.reject(new Error(`${url}: HTTP ${r.status}`)))
    const item = (group, title, detail, url, flag = '') => ({ group, title, detail, url, flag, key: norm(`${title} ${detail}`) })

    function deviceItems(json) {
        const out = []
        const walk = (nodes, parent) => (nodes || []).forEach(n => {
            const d = n.data
            const title = stripHtml(d.name)
            const detail = [d.secondaryName, d.type, d.roomName, parent && `child of ${parent}`].filter(Boolean).join(' · ')
            out.push(item('Device', title, detail, `/device/edit/${d.id}`, d.disabled ? 'disabled' : ''))
            walk(n.children, title)
        })
        walk(json.devices)
        return out
    }

    function appItems(json) {
        const out = []
        // Child app types are not in the type lists, so a child takes its top-level parent's menu.
        const menuByType = {}
        ;[...(json.systemAppTypes || []), ...(json.userAppTypes || [])].forEach(t => { menuByType[t.id] = t.menu })
        // A dashboard is a child of the Hubitat Dashboards or Easy Dashboards parent app.
        const groupFor = (d, menu, parent) => parent && /dashboard/i.test(d.type) ? 'Dashboard'
            : menu === 'Automations' ? 'Automation' : menu === 'Integrations' ? 'Integration' : 'App'
        const walk = (nodes, menu, parent) => (nodes || []).forEach(n => {
            const d = n.data
            const m = menu || menuByType[d.appTypeId]
            const title = stripHtml(d.name)
            const paused = /paused/i.test(d.name) && title !== d.name
            out.push(item(groupFor(d, m, parent), title, [d.type, parent && `in ${parent}`].filter(Boolean).join(' · '),
                `/installedapp/configure/${d.id}`, d.disabled ? 'disabled' : paused ? 'paused' : ''))
            walk(n.children, m, title)
        })
        walk(json.apps)
        return out
    }

    const SOURCES = {
        '/hub2/devicesList': deviceItems,
        '/hub2/appsList': appItems,
        '/room/listRoomsJson': json => json.map(r => item('Room', r.name, '', '/room/list')),
        '/hub2/userAppTypes': json => json.map(t => item('App code', t.name, t.namespace, `/app/editor/${t.id}`)),
        '/hub2/userDeviceTypes': json => json.map(t => item('Driver code', t.name, t.namespace, `/driver/editor/${t.id}`)),
        '/hub2/userLibraries': json => json.map(t => item('Library', t.name, [t.namespace, t.description].filter(Boolean).join(' · '), `/library/editor/${t.id}`)),
        '/hub2/userBundles': json => json.map(t => item('Bundle', t.name, t.namespace, `/bundle/editor/${t.id}`)),
    }

    // Some lists take several seconds, so the index persists across page loads and each list refreshes on its own.
    let index = readStore()
    let errors = {}
    let refreshing = false

    function readStore() {
        try { return JSON.parse(sessionStorage.getItem(STORE_KEY)) || { time: 0, bySource: {} } } catch (e) { return { time: 0, bySource: {} } }
    }

    function writeStore() {
        try { sessionStorage.setItem(STORE_KEY, JSON.stringify(index)) } catch (e) { /* storage full or blocked */ }
    }

    const allItems = () => Object.values(index.bySource).flat()

    function refresh(onUpdate) {
        if (refreshing || Date.now() - index.time < CACHE_MS) return
        refreshing = true
        errors = {}
        const started = Date.now()
        Promise.allSettled(Object.entries(SOURCES).map(([url, build]) => getJson(url).then(json => {
            index.bySource[url] = build(json)
            delete errors[url]
            onUpdate()
        }, e => {
            errors[url] = e.message
            onUpdate()
        }))).then(() => {
            index.time = Object.keys(errors).length ? 0 : started
            writeStore()
            refreshing = false
            onUpdate()
        })
    }

    function search(items, query) {
        const terms = norm(query).split(/\s+/).filter(Boolean)
        if (!terms.length) return []
        return items.filter(i => terms.every(t => i.key.includes(t)))
    }

    const css = `
#hgs-overlay{position:fixed;inset:0;z-index:100000;background:rgba(0,0,0,.35);display:flex;justify-content:center;align-items:flex-start;padding-top:10vh}
#hgs-panel{background:#fff;color:#222;width:min(720px,92vw);max-height:75vh;display:flex;flex-direction:column;border-radius:8px;box-shadow:0 10px 40px rgba(0,0,0,.3);font:14px/1.4 system-ui,sans-serif}
#hgs-input{border:0;border-bottom:1px solid #ddd;padding:14px 16px;font-size:16px;outline:none;border-radius:8px 8px 0 0}
#hgs-results{overflow-y:auto;padding:4px 0}
#hgs-results .hgs-group{padding:8px 16px 2px;font-size:11px;font-weight:600;text-transform:uppercase;color:#888}
#hgs-results a{display:block;padding:6px 16px;color:inherit!important;text-decoration:none}
#hgs-results a.hgs-active,#hgs-results a:hover{background:#e8f0fe}
#hgs-results .hgs-detail{color:#777;font-size:12px}
#hgs-results .hgs-flag{color:#b55;font-size:11px;margin-left:6px}
#hgs-results .hgs-msg{padding:10px 16px;color:#777}
#hgs-button{position:fixed;right:14px;bottom:14px;z-index:99999;border:0;border-radius:20px;padding:8px 14px;background:#1a73e8;color:#fff;font:13px system-ui,sans-serif;cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,.25)}
@media (prefers-color-scheme: dark){#hgs-panel{background:#222;color:#eee}#hgs-input{background:#222;color:#eee;border-color:#444}#hgs-results a.hgs-active,#hgs-results a:hover{background:#33415c}}`

    let overlay = null
    let active = -1

    function render(resultsEl, query) {
        const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))
        const hits = search(allItems(), query)
        const failed = Object.values(errors)
        let html = ''
        if (failed.length) html += `<div class="hgs-msg">Could not load: ${esc(failed.join(', '))}</div>`
        if (refreshing && !Object.keys(index.bySource).length) html += `<div class="hgs-msg">Loading…</div>`
        else if (query.trim() && !hits.length) html += `<div class="hgs-msg">No match${refreshing ? ' yet, still loading' : ''}</div>`
        for (const g of GROUP_ORDER) {
            const rows = hits.filter(h => h.group === g)
            if (!rows.length) continue
            html += `<div class="hgs-group">${g} (${rows.length})</div>`
            for (const r of rows.slice(0, MAX_PER_GROUP)) {
                html += `<a href="${esc(r.url)}">${esc(r.title)}${r.flag ? `<span class="hgs-flag">${r.flag}</span>` : ''}` +
                    (r.detail ? `<div class="hgs-detail">${esc(r.detail)}</div>` : '') + '</a>'
            }
            if (rows.length > MAX_PER_GROUP) html += `<div class="hgs-msg">${rows.length - MAX_PER_GROUP} more, refine the search</div>`
        }
        resultsEl.innerHTML = html
        active = -1
    }

    function setActive(resultsEl, delta) {
        const links = [...resultsEl.querySelectorAll('a')]
        if (!links.length) return
        links[active]?.classList.remove('hgs-active')
        active = (active + delta + links.length) % links.length
        links[active].classList.add('hgs-active')
        links[active].scrollIntoView({ block: 'nearest' })
    }

    function open() {
        if (overlay) return
        overlay = document.createElement('div')
        overlay.id = 'hgs-overlay'
        overlay.innerHTML = '<div id="hgs-panel"><input id="hgs-input" type="search" placeholder="Search devices, apps, rules, dashboards, rooms, code" autocomplete="off"><div id="hgs-results"></div></div>'
        document.body.appendChild(overlay)
        const input = overlay.querySelector('#hgs-input')
        const resultsEl = overlay.querySelector('#hgs-results')
        overlay.addEventListener('mousedown', e => { if (e.target === overlay) close() })
        input.focus()
        const update = () => { if (overlay) render(resultsEl, input.value) }
        input.addEventListener('input', update)
        refresh(update)
        update()
        input.addEventListener('keydown', e => {
            if (e.key === 'ArrowDown') { e.preventDefault(); setActive(resultsEl, 1) }
            else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(resultsEl, -1) }
            else if (e.key === 'Enter') {
                const links = resultsEl.querySelectorAll('a')
                const target = links[active] || links[0]
                if (target) e.metaKey || e.ctrlKey ? window.open(target.href, '_blank') : (location.href = target.href)
            }
        })
    }

    function close() {
        overlay?.remove()
        overlay = null
    }

    const style = document.createElement('style')
    style.textContent = css
    document.head.appendChild(style)

    const button = document.createElement('button')
    button.id = 'hgs-button'
    button.textContent = 'Search ⌘K'
    button.title = 'Search everything on this hub (Ctrl+K or Cmd+K)'
    button.addEventListener('click', open)
    document.body.appendChild(button)

    document.addEventListener('keydown', e => {
        if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); overlay ? close() : open() }
        else if (e.key === 'Escape' && overlay) close()
    }, true)
})()
