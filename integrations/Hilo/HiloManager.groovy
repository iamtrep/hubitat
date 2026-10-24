// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 * Hilo Manager — Parent Integration App
 *
 * Logs in to a Hilo account, reads one location's hourly consumption from Hilo's
 * history endpoint, and publishes it on a Hilo Energy Meter child device. The
 * child receives whole-home power by push from Hilo's GraphQL subscription and
 * asks this app for an access token on each connect.
 *
 * One instance serves one Hilo location; add another instance for another location.
 *
 * Login borrows the Home Assistant integration's public client, whose only
 * registered redirect is my.home-assistant.io, so the user pastes the URL they
 * land on after logging in. Client id, scope and subscription key are those of
 * python-hilo (MIT). No source incorporated.
 */

import com.hubitat.app.ChildDeviceWrapper
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.transform.Field
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

definition(
    name: "Hilo Manager",
    namespace: "iamtrep",
    author: "pj",
    description: "Unofficial Hilo (Hydro-Québec) integration: hourly consumption and live power",
    menu: "Integrations",
    category: "Utility",
    // Installed when first opened, so leaving the page before Done keeps the instance.
    installOnOpen: true,
    // Token refresh, poll callbacks and child calls all read and rewrite the tokens.
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/Hilo/HiloManager.groovy",
    documentationLink: "https://github.com/iamtrep/hubitat/tree/main/integrations/Hilo",
    iconUrl: "",
    iconX2Url: ""
)

@Field static final String CODE_VERSION = "0.1.0"

@Field static final String AUTH_URL = "https://connexion.hiloenergie.com/HiloDirectoryB2C.onmicrosoft.com/B2C_1A_SIGN_IN/oauth2/v2.0/"
@Field static final String CLIENT_ID = "1ca9f585-4a55-4085-8e30-9746a65fa561"
@Field static final String REDIRECT_URI = "https://my.home-assistant.io/redirect/oauth"
@Field static final String SCOPE = "openid https://HiloDirectoryB2C.onmicrosoft.com/hiloapis/user_impersonation offline_access"
@Field static final String SUBSCRIPTION_KEY = "20eeaedcb86945afa3fe792cea89b8bf"
@Field static final String API_URL = "https://api.hiloenergie.com/Automation/v1/api"
// Version kept in step with CODE_VERSION by hand: a static initializer cannot read another @Field.
@Field static final String USER_AGENT = "HiloManager/0.1.0 (Hubitat)"

@Field static final String DNI_PREFIX = "hilo-"
@Field static final String DRIVER_METER = "Hilo Energy Meter"
@Field static final String VIEWER_FILE = "hilo-history.html"
@Field static final String WC_VIEWER_FILE = "hilo-winter-credit.html"
@Field static final String DRIVER_PEAK = "Hilo Winter Credit"
// Hydro-Québec open data: peak events of the residential winter credit.
@Field static final String PEAK_EVENTS_URL = "https://donnees.hydroquebec.com/api/explore/v2.1/catalog/datasets/evenements-pointe/records"
@Field static final String PEAK_OFFER = "CPC-D"
// Reference days can reach about three weeks back over the holidays; read this far.
@Field static final int PEAK_LOOKBACK_DAYS = 45

@Field static final long TOKEN_REFRESH_BUFFER_MS = 300_000L
@Field static final int HTTP_TIMEOUT = 20
@Field static final int DEBUG_LOG_TIMEOUT = 1800
// How long the app page redraws itself while waiting for the device it asked for.
@Field static final long CHILD_WAIT_MS = 30_000L
// Hourly polls: warn on each failure below this many in a row, then one error,
// then a warn every OUTAGE_REMINDER_MS until they clear.
@Field static final int FAILURE_ERROR_THRESHOLD = 3
@Field static final long OUTAGE_REMINDER_MS = 3_600_000L
// Longest Retry-After honored, so a bad header can't stop polling for good.
@Field static final long RETRY_AFTER_MAX_MS = 3_600_000L
@Field static final int LOG_BODY_MAX = 200
// History files: seconds between days read into File Manager while catching up (one call
// a minute, the Home Assistant integration's minimum poll), the furthest back the start of
// the data is looked for, and how long an unanswered history request blocks the next one.
@Field static final int BACKFILL_INTERVAL_SECS = 60
@Field static final int HISTORY_MAX_WEEKS = 520
@Field static final long HISTORY_STALE_MS = 600_000L

// Access tokens, in memory only: [token, expiresAt]. Keyed by app id, since every
// instance of the app shares this map. Lost on reboot or code push, which costs one refresh.
@Field static final ConcurrentHashMap<String, List> ACCESS = new ConcurrentHashMap<>()
// Decrypted secrets: decrypt() costs ~350 ms on a C-8 Pro, ~830 ms on a C-7.
// Keyed by app id + state key; the stored ciphertext rides along so a rotated value misses.
@Field static final ConcurrentHashMap<String, List<String>> SECRET_CACHE = new ConcurrentHashMap<>()

// ── Interface text ────────────────────────────────────────────────────
// What the app page shows, in English and French. Logs stay in English and never come from
// here. {name} placeholders are filled by tr(). French follows Hydro-Québec's Tarifs
// d'électricité (article 2.59) for the winter credit terms. Hub UI elements (Done, File Manager)
// keep their English names, since the hub's interface is English only.
@Field static final Map<String, Map<String, String>> I18N = [
    en: [
        "sec.login"         : "Log in",
        "login.step1"       : "1. Log in to Hilo. After the login you land on a my.home-assistant.io page. Don't click anything on it.",
        "login.link"        : "Log in to Hilo",
        "login.linkDesc"    : "Opens Hilo's login page in a new tab",
        "login.paste"       : "2. Copy the address of that page from the browser's address bar and paste it here",
        "sec.location"      : "Location",
        "loc.input"         : "Hilo location",
        "loc.none"          : "No location found on this Hilo account.",
        "sec.wc"            : "Winter credit settings",
        "wc.enable"         : "Compute the reference energy of each peak event the evening before",
        "wc.floor"          : "kWh the home uses during a 4-hour event with the heating cut",
        "wc.extra"          : "Events missing from Hydro-Québec's open data (e.g. 2025-12-07 PM, 2026-01-05 AM)",
        "wc.notify"         : "Notify",
        "wc.notifyTime"     : "Evening notification at",
        "wc.notifyDaily"    : "Notify every winter evening, even without an event",
        "wc.computeNow"     : "Compute now",
        "sec.name"          : "Name and logging",
        "name.label"        : "Name of this instance",
        "log.info"          : "Enable info logging",
        "log.debug"         : "Enable debug logging",
        "log.trace"         : "Enable trace logging",
        "sec.account"       : "Account",
        "acct.loggedIn"     : "Logged in to Hilo, location <b>{name}</b>.",
        "acct.logout"       : "Log out of Hilo",
        "err.pollStreak"    : "History reads failing since {time}",
        "err.refused"       : "Hilo refused the login: {why}",
        "err.noCode"        : "No login code in the pasted address. Paste the whole address of the page you landed on.",
        "err.oldLink"       : "That address comes from an older login link. Log in again with the link below.",
        "err.loginFailed"   : "Login failed: {why}",
        "err.locations"     : "Could not read the Hilo locations: {why}",
        "err.loginLost"     : "Hilo refused the saved login. Log in again.",
        "err.child"         : "Could not create the device ({why}). Is this location already connected in another Hilo Manager?",
        "energy.title"      : "Energy · {name}",
        "energy.creating"   : "Creating the device…",
        "energy.noDevice"   : "No device.",
        "energy.onDone"     : "The device is created when you press Done.",
        "energy.now"        : "now",
        "energy.today"      : "Today",
        "energy.yesterday"  : "Yesterday",
        "energy.history"    : "History",
        "energy.file"       : "File Manager, {file}",
        "energy.lastRead"   : "Last read",
        "hist.searching"    : "looking for where it starts",
        "hist.notRead"      : "not read yet",
        "hist.left"         : "from {start}, {n} days still to read",
        "hist.complete"     : "complete from {start}",
        "btn.device"        : "Device",
        "btn.history"       : "Browse the history",
        "btn.files"         : "File Manager",
        "btn.wcAnalysis"    : "Winter credit analysis",
        "wc.title"          : "Winter credit",
        "wc.off"            : "Off. Turn it on under Winter credit settings to compute each peak event's reference energy the evening before.",
        "wc.none"           : "Nothing computed yet. Reference energies are computed from December to March after each hourly read, or now with Compute now.",
        "wc.tagEvent"       : "Peak event",
        "wc.tagNotAnnounced": "Not announced",
        "wc.capped"         : "Reference energy with the limit",
        "wc.cappedHilo"     : "Hilo: adjusted consumption limit",
        "wc.cappedNote"     : "applies when more than 40 kWh is curtailed",
        "wc.anchorPlan"     : "Anchor period plan",
        "wc.planAtMost"     : "at most",
        "wc.planAtLeast"    : "at least",
        "wc.anchorSoFar"    : "Anchor period so far",
        "wc.curtailed"      : "Energy curtailed",
        "wc.curtailedNote"  : "cutting to {floor} kWh",
        "wc.computed"       : "Computed {time} · {n} Hydro-Québec events on file from the last {days} days",
        "advice.keep"       : "<b>Consume {use} kWh during {during}.</b> That curtails exactly 40 kWh. Cutting to {floor} kWh would curtail more than 40 kWh, so the limit applies and only {deep} kWh counts.",
        "advice.cut"        : "<b>Cut as deep as you can during {during}.</b> Cutting to {floor} kWh curtails {c} kWh.",
        "advice.cutLimit"   : "<b>Cut as deep as you can during {during}.</b> Cutting to {floor} kWh curtails {c} kWh, with the limit.",
        "ref.label"         : "Reference energy",
        "ref.hilo"          : "Hilo: original consumption limit",
        "ref.parts"         : "{base} kWh + {adj} kWh temperature adjustment",
        "ref.soFar"         : "{v} kWh so far",
        "ref.soFarNote"     : "rises with the consumption until {end}",
        "ref.knownAt"       : "known at {end}",
        "ref.knownAtNote"   : "{base} kWh + the temperature adjustment from {anchor}",
        "notif.head"        : "Hilo: peak event {day} {window}.",
        "notif.headIf"      : "Hilo: if {day} {window} were a peak event:",
        "notif.capped"      : "Reference energy with the limit {capped} kWh:",
        "notif.atMost"      : "consume at most {target} kWh during {anchor}, then cut to {floor} kWh: 40 kWh curtailed.",
        "notif.atLeast"     : "consume at least {target} kWh during {anchor}, then cut as deep as you can: about {cut} kWh curtailed.",
        "notif.afterKeep"   : "After {end}: reference energy {target} kWh. Consume {use} kWh during the event to curtail exactly 40 kWh; cutting to {floor} kWh curtails only {cut} kWh.",
        "notif.afterCut"    : "After {end}: reference energy {target} kWh; cut to {floor} kWh to curtail {cut} kWh.",
        "notif.missing"     : "Provisional: {n} reference hours not in yet.",
        "health.loggedOut"  : "logged out of Hilo",
        "health.loginAgain" : "log in to Hilo again",
        "health.unreachable": "Hilo unreachable",
        "health.back"       : "Hilo back after {d} ({n} failures)",
        "dev.peakLabel"     : "Hilo {name} winter credit",
    ],
    fr: [
        "sec.login"         : "Connexion",
        "login.step1"       : "1. Connectez-vous à Hilo. Après la connexion, vous arrivez sur une page de my.home-assistant.io. Ne cliquez sur rien dans cette page.",
        "login.link"        : "Se connecter à Hilo",
        "login.linkDesc"    : "Ouvre la page de connexion de Hilo dans un nouvel onglet",
        "login.paste"       : "2. Copiez l'adresse de cette page dans la barre d'adresse du navigateur et collez-la ici",
        "sec.location"      : "Emplacement",
        "loc.input"         : "Emplacement Hilo",
        "loc.none"          : "Aucun emplacement trouvé dans ce compte Hilo.",
        "sec.wc"            : "Réglages du crédit hivernal",
        "wc.enable"         : "Calculer l'énergie de référence de chaque événement de pointe la veille au soir",
        "wc.floor"          : "kWh consommés par le domicile pendant un événement de 4 heures, chauffage coupé",
        "wc.extra"          : "Événements absents des données ouvertes d'Hydro-Québec (ex. 2025-12-07 PM, 2026-01-05 AM)",
        "wc.notify"         : "Notifier",
        "wc.notifyTime"     : "Notification du soir à",
        "wc.notifyDaily"    : "Notifier chaque soir d'hiver, même sans événement",
        "wc.computeNow"     : "Calculer maintenant",
        "sec.name"          : "Nom et journaux",
        "name.label"        : "Nom de cette instance",
        "log.info"          : "Activer les journaux d'information",
        "log.debug"         : "Activer les journaux de débogage",
        "log.trace"         : "Activer les journaux de trace",
        "sec.account"       : "Compte",
        "acct.loggedIn"     : "Connecté à Hilo, emplacement <b>{name}</b>.",
        "acct.logout"       : "Se déconnecter de Hilo",
        "err.pollStreak"    : "La lecture de l'historique échoue depuis {time}",
        "err.refused"       : "Hilo a refusé la connexion : {why}",
        "err.noCode"        : "Aucun code de connexion dans l'adresse collée. Collez l'adresse complète de la page où vous êtes arrivé.",
        "err.oldLink"       : "Cette adresse vient d'un ancien lien de connexion. Reconnectez-vous avec le lien ci-dessous.",
        "err.loginFailed"   : "La connexion a échoué : {why}",
        "err.locations"     : "Impossible de lire les emplacements Hilo : {why}",
        "err.loginLost"     : "Hilo a refusé la connexion enregistrée. Reconnectez-vous.",
        "err.child"         : "Impossible de créer l'appareil ({why}). Cet emplacement est-il déjà relié à une autre instance de Hilo Manager?",
        "energy.title"      : "Énergie · {name}",
        "energy.creating"   : "Création de l'appareil…",
        "energy.noDevice"   : "Aucun appareil.",
        "energy.onDone"     : "L'appareil est créé quand vous appuyez sur Done.",
        "energy.now"        : "en ce moment",
        "energy.today"      : "Aujourd'hui",
        "energy.yesterday"  : "Hier",
        "energy.history"    : "Historique",
        "energy.file"       : "File Manager, {file}",
        "energy.lastRead"   : "Dernière lecture",
        "hist.searching"    : "recherche de son début",
        "hist.notRead"      : "pas encore lu",
        "hist.left"         : "depuis le {start}, encore {n} jours à lire",
        "hist.complete"     : "complet depuis le {start}",
        "btn.device"        : "Appareil",
        "btn.history"       : "Parcourir l'historique",
        "btn.files"         : "File Manager",
        "btn.wcAnalysis"    : "Analyse du crédit hivernal",
        "wc.title"          : "Crédit hivernal",
        "wc.off"            : "Désactivé. Activez-le dans Réglages du crédit hivernal pour calculer l'énergie de référence de chaque événement de pointe la veille au soir.",
        "wc.none"           : "Rien n'est encore calculé. Les énergies de référence sont calculées de décembre à mars après chaque lecture horaire, ou tout de suite avec Calculer maintenant.",
        "wc.tagEvent"       : "Événement de pointe",
        "wc.tagNotAnnounced": "Non annoncé",
        "wc.capped"         : "Énergie de référence avec la limite",
        "wc.cappedHilo"     : "Hilo : limite de consommation ajustée",
        "wc.cappedNote"     : "s'applique quand plus de 40 kWh sont effacés",
        "wc.anchorPlan"     : "Plan pour la période d'ancrage",
        "wc.planAtMost"     : "au plus",
        "wc.planAtLeast"    : "au moins",
        "wc.anchorSoFar"    : "Période d'ancrage jusqu'ici",
        "wc.curtailed"      : "Énergie effacée",
        "wc.curtailedNote"  : "en réduisant à {floor} kWh",
        "wc.computed"       : "Calculé le {time} · {n} événements d'Hydro-Québec enregistrés pour les {days} derniers jours",
        "advice.keep"       : "<b>Consommez {use} kWh pendant {during}.</b> Cela efface exactement 40 kWh. Réduire à {floor} kWh effacerait plus de 40 kWh : la limite s'appliquerait et seuls {deep} kWh compteraient.",
        "advice.cut"        : "<b>Réduisez au maximum pendant {during}.</b> Réduire à {floor} kWh efface {c} kWh.",
        "advice.cutLimit"   : "<b>Réduisez au maximum pendant {during}.</b> Réduire à {floor} kWh efface {c} kWh, avec la limite.",
        "ref.label"         : "Énergie de référence",
        "ref.hilo"          : "Hilo : limite de consommation originale",
        "ref.parts"         : "{base} kWh + {adj} kWh d'ajustement pour la température",
        "ref.soFar"         : "{v} kWh jusqu'ici",
        "ref.soFarNote"     : "augmente avec la consommation jusqu'à {end}",
        "ref.knownAt"       : "connue à {end}",
        "ref.knownAtNote"   : "{base} kWh + l'ajustement pour la température de {anchor}",
        "notif.head"        : "Hilo : événement de pointe le {day}, {window}.",
        "notif.headIf"      : "Hilo : si le {day}, {window} était un événement de pointe :",
        "notif.capped"      : "Énergie de référence avec la limite {capped} kWh :",
        "notif.atMost"      : "consommez au plus {target} kWh pendant {anchor}, puis réduisez à {floor} kWh : 40 kWh effacés.",
        "notif.atLeast"     : "consommez au moins {target} kWh pendant {anchor}, puis réduisez au maximum : environ {cut} kWh effacés.",
        "notif.afterKeep"   : "Après {end} : énergie de référence {target} kWh. Consommez {use} kWh pendant l'événement pour effacer exactement 40 kWh; réduire à {floor} kWh n'efface que {cut} kWh.",
        "notif.afterCut"    : "Après {end} : énergie de référence {target} kWh; réduisez à {floor} kWh pour effacer {cut} kWh.",
        "notif.missing"     : "Provisoire : {n} heures de référence pas encore reçues.",
        "health.loggedOut"  : "déconnecté de Hilo",
        "health.loginAgain" : "reconnectez-vous à Hilo",
        "health.unreachable": "Hilo injoignable",
        "health.back"       : "Hilo de retour après {d} ({n} échecs)",
        "dev.peakLabel"     : "Hilo {name} crédit hivernal",
    ],
] // end I18N

@Field static final Map<String, String> LANGUAGES = [fr: "Français", en: "English"]

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    syncLanguage()
    if (settings.pastedUrl) {
        redeemPastedUrl(settings.pastedUrl as String)
        app.removeSetting("pastedUrl")
    }
    if (settings.locationId && settings.locationId != state.locationId) selectLocation(settings.locationId as String)

    // Redraw while the device from selectLocation() is on its way, so it shows without a reload.
    boolean childPending = state.locationId && !state.childError && !meterChild() &&
        now() - ((state.childRequestedAt ?: 0L) as long) < CHILD_WAIT_MS
    dynamicPage(name: "mainPage", install: true, uninstall: true, refreshInterval: childPending ? 2 : 0) {
        // The style and any error ride in the page's first section, so no empty section opens it.
        String head = uiStyle() + uiErrors()
        if (!isLoggedIn()) {
            if (!state.codeVerifier) newPkce()
            section(tr("sec.login"), sectionClass: "hilo-sec") {
                paragraph rawHtml: true, head
                languageInput()
                paragraph tr("login.step1")
                href url: authorizeUrl(), style: "external", title: tr("login.link"), description: tr("login.linkDesc")
                input "pastedUrl", "text", title: tr("login.paste"), submitOnChange: true, required: false
            }
        } else if (!state.locationId) {
            section(tr("sec.location"), sectionClass: "hilo-sec") {
                paragraph rawHtml: true, head
                languageInput()
                Map<String, String> opts = ((state.locations ?: []) as List<Map>).collectEntries { [(it.id as String): it.name as String] }
                if (opts) {
                    input "locationId", "enum", title: tr("loc.input"), options: opts, submitOnChange: true, required: true
                } else {
                    paragraph tr("loc.none")
                }
            }
        } else {
            section {
                paragraph rawHtml: true, head + "<div class='flex flex-wrap gap-3'>${energyCard(childPending)}${winterCreditCard()}</div>"
                languageInput()
            }
            // Open while the feature is off, so its switch is in view.
            section(tr("sec.wc"), hideable: true, hidden: settings.wcEnable as boolean) {
                input "wcEnable", "bool", title: tr("wc.enable"), defaultValue: false, submitOnChange: true
                if (settings.wcEnable) {
                    input "wcFloor", "decimal", title: tr("wc.floor"), defaultValue: 3.0, range: "0..40", width: 6
                    input "wcExtraEvents", "text", title: tr("wc.extra"), required: false, width: 6
                    input "wcNotify", "capability.notification", title: tr("wc.notify"), multiple: true, required: false, width: 6
                    input "wcNotifyTime", "time", title: tr("wc.notifyTime"), defaultValue: "22:00", required: false, width: 6
                    input "wcNotifyDaily", "bool", title: tr("wc.notifyDaily"), defaultValue: false
                    input "btnPeakNow", "button", title: tr("wc.computeNow"), inputClass: "p-button p-button-outlined"
                }
            }
        }
        section(tr("sec.name"), hideable: true, hidden: true) {
            label title: tr("name.label"), required: false
            input "txtEnable", "bool", title: tr("log.info"), defaultValue: true
            input "debugEnable", "bool", title: tr("log.debug"), defaultValue: false, submitOnChange: true
            if (settings.debugEnable) input "traceEnable", "bool", title: tr("log.trace"), defaultValue: false
        }
        if (isLoggedIn()) {
            section(tr("sec.account"), hideable: true, hidden: true) {
                if (state.locationName) paragraph tr("acct.loggedIn", [name: htmlEsc(state.locationName)])
                input "btnLogout", "button", title: tr("acct.logout"), inputClass: "p-button p-button-outlined"
            }
        }
    }
}

// ── Interface language ────────────────────────────────────────────────

// French unless set otherwise: a Hilo account means a Hydro-Québec customer, so a home in Québec.
private String uiLang() { return (settings.uiLang ?: "fr") as String }

// The devices word their events and preferences in the page's language, and the two HTML
// pages read it from hilo-<location>-ui.json when opened without #lang=.
private void syncLanguage() {
    if (state.uiLangSynced == uiLang() || !state.locationId) return
    [meterChild(), peakChild()].findAll { it }.each { it.setLanguage(uiLang()) }
    try {
        uploadHubFile(uiFile(), JsonOutput.toJson([lang: uiLang()]).getBytes("UTF-8"))
    } catch (Exception e) {
        logWarn "could not write ${uiFile()}: ${getObjectClassName(e)}: ${e.message}"
        return
    }
    state.uiLangSynced = uiLang()
}

private String uiFile() { return "hilo-${state.locationId}-ui.json" }

// A link to one of the HTML pages, in the page's language.
private String viewerUrl(String file) { return "/local/${file}#lang=${uiLang()}" }

// Its title is in both languages so either reader finds it.
private void languageInput() {
    input "uiLang", "enum", title: "Langue / Language", options: LANGUAGES, defaultValue: "fr", submitOnChange: true, width: 3
}

// The text for key in lang (the page's language by default), {name} placeholders filled from args.
// A key missing in French falls back to English, then to the key itself.
private String tr(String key, Map args = [:], String lang = null) {
    String s = I18N[lang ?: uiLang()]?.get(key) ?: I18N.en.get(key) ?: key
    args.each { k, v -> s = s.replace("{${k}}".toString(), v == null ? "" : v.toString()) }
    return s
}

// A message kept in state as [key, args], shown in the page's language when it renders.
// A plain String (written before the page was bilingual) shows as it is.
private String trMsg(msg) {
    if (msg instanceof Map) return tr((msg as Map).key as String, ((msg as Map).args ?: [:]) as Map)
    return msg?.toString()
}

// ── Main page parts ───────────────────────────────────────────────────

private static String htmlEsc(v) {
    return v == null ? "" : v.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace('"', "&quot;")
}

// French writes the decimal comma.
private String kwh(v, int digits = 1, String lang = null) {
    if (v == null) return "–"
    String s = String.format("%.${digits}f", (v as BigDecimal).doubleValue())
    return (lang ?: uiLang()) == "fr" ? s.replace(".", ",") : s
}

private static String uiStyle() {
    return """<style>
.hilo-sec > :first-child { font-size: 1.05rem; font-weight: 600; padding-bottom: 4px; margin-bottom: 6px; border-bottom: 1px solid var(--surface-border, rgba(128,128,128,.3)); }
.hilo-kv { display: grid; grid-template-columns: max-content 1fr; gap: 4px 16px; }
.hilo-kv .k { color: var(--text-color-secondary); }
.hilo-kv small, .hilo-card .note { display: block; color: var(--text-color-secondary); font-size: .8rem; }
.hilo-card { flex: 1 1 280px; min-width: 260px; }
.hilo-card h4 { margin: 0 0 8px; font-size: 1rem; font-weight: 600; }
.hilo-big { font-size: 1.6rem; font-weight: 600; font-variant-numeric: tabular-nums; }
.hilo-btn { display: inline-block; padding: 4px 12px; margin: 8px 8px 0 0; border: 1px solid var(--surface-border, rgba(128,128,128,.4)); border-radius: 6px; text-decoration: none; }
</style>"""
}

private String uiErrors() {
    List errs = [state.loginError, state.childError].findAll { it }.collect { trMsg(it) }
    if (state.pollStreak) errs << tr("err.pollStreak", [time: formatClock((state.pollStreak as Map).since as long)])
    return errs ? "<div class='p-message p-message-error p-3 border-round'>${errs.collect { htmlEsc(it) }.join('<br>')}</div>" : ""
}

// rows: [label, value, optional note under the value]
private static String kvRows(List<List> rows) {
    return "<div class='hilo-kv'>" + rows.collect { "<span class='k'>${it[0]}</span><span>${it[1]}${it.size() > 2 && it[2] ? "<small>${it[2]}</small>" : ''}</span>" }.join("") + "</div>"
}

private static String linkButton(String href, String text) { return "<a class='hilo-btn' href='${href}' target='_blank'>${text}</a>" }

private static String card(String title, String body) {
    return "<div class='hilo-card border-1 surface-border border-round p-3'><h4>${title}</h4>${body}</div>"
}

private String historyLine() {
    if (!state.historyStart) return tr(historyBusy() ? "hist.searching" : "hist.notRead")
    long left = localToday().minusDays(1).toEpochDay() - LocalDate.parse(state.lastCompleteDay as String).toEpochDay() - 1
    return left > 0 ? tr("hist.left", [start: state.historyStart, n: left]) : tr("hist.complete", [start: state.historyStart])
}

private String energyCard(boolean childPending) {
    ChildDeviceWrapper m = meterChild()
    String title = tr("energy.title", [name: htmlEsc(state.locationName)])
    if (!m) return card(title, tr(childPending ? "energy.creating" : (state.childError ? "energy.noDevice" : "energy.onDone")))
    String body = "<div class='hilo-big'>${kwh(m.currentValue('power'), 0)} W</div><div class='note mb-2'>${tr('energy.now')}</div>" +
        kvRows([[tr("energy.today"), "${kwh(m.currentValue('energy'))} kWh"], [tr("energy.yesterday"), "${kwh(m.currentValue('energyYesterday'))} kWh"],
                [tr("energy.history"), historyLine(), tr("energy.file", [file: historyFile('YYYY-MM')])],
                [tr("energy.lastRead"), state.lastPoll ? formatClock(state.lastPoll as long) : "–"]])
    body += linkButton("/device/edit/${m.id}", tr("btn.device")) + (viewerInstalled(VIEWER_FILE) ? linkButton(viewerUrl(VIEWER_FILE), tr("btn.history")) : "") +
        linkButton("/hub/fileManager", tr("btn.files"))
    return card(title, body)
}

private String winterCreditCard() {
    if (!settings.wcEnable) return card(tr("wc.title"), "<div class='note'>${tr('wc.off')}</div>")
    ChildDeviceWrapper w = peakChild()
    if (!w || !w.currentValue("eventDate")) return card(tr("wc.title"), "<div class='note'>${tr('wc.none')}</div>")
    String day = w.currentValue("eventDate"), win = w.currentValue("window")
    boolean am = win == "AM", announced = w.currentValue("announced") == "true"
    String anchor = am ? "01:00–04:00" : "12:00–14:00"
    String tag = announced ? "<span class='p-tag p-tag-warning mr-2'>${tr('wc.tagEvent')}</span>" : "<span class='p-tag p-tag-secondary mr-2'>${tr('wc.tagNotAnnounced')}</span>"
    boolean live = w.currentValue("anchorWindow") == "${day} ${am ? '01:00-04:00' : '12:00-14:00'}"
    // anchorPlan is an attribute value ("at most" / "at least"), shown in the page's language.
    String plan = w.currentValue("anchorPlan")
    String planText = plan == "at most" ? tr("wc.planAtMost") : (plan == "at least" ? tr("wc.planAtLeast") : htmlEsc(plan))
    String body = "<div class='mb-2'>${tag}${htmlEsc(day)} ${am ? '06:00–10:00' : '16:00–20:00'}</div>" + adviceBox(w, am) +
        kvRows([referenceEnergyRow(w, am, live, anchor),
                ["${tr('wc.capped')}<small>${tr('wc.cappedHilo')}</small>", "${kwh(w.currentValue('cappedTarget'))} kWh", tr("wc.cappedNote")],
                [tr("wc.anchorPlan"), "${planText} ${kwh(w.currentValue('anchorTarget'))} kWh", anchor],
                [tr("wc.anchorSoFar"), live ? "${kwh(w.currentValue('anchorUsed'))} kWh" : "–"],
                [tr("wc.curtailed"), "${kwh(w.currentValue('expectedCurtailment'))} kWh", tr("wc.curtailedNote", [floor: kwh(settings.wcFloor ?: 3.0)])]])
    body += linkButton("/device/edit/${w.id}", tr("btn.device")) + (viewerInstalled(WC_VIEWER_FILE) ? linkButton(viewerUrl(WC_VIEWER_FILE), tr("btn.wcAnalysis")) : "")
    Map last = state.peakLast as Map
    if (last?.at) body += "<div class='note mt-2'>${tr('wc.computed', [time: formatClock(last.at as long), n: ((state.peakEvents ?: []) as List).size(), days: PEAK_LOOKBACK_DAYS])}</div>"
    return card(tr("wc.title"), body)
}

// Once the anchor period is over the device knows what pays during the event: cutting deep, or consuming
// just enough to curtail exactly 40 kWh when the limit would otherwise cut a deep curtailment below 40.
private String adviceBox(ChildDeviceWrapper w, boolean am) {
    String advice = w.currentValue("advice")
    BigDecimal target = w.currentValue("target") as BigDecimal, capped = w.currentValue("cappedTarget") as BigDecimal
    if (target == null || capped == null || !(advice in ["keep", "cut"])) return ""
    BigDecimal floorKWh = (settings.wcFloor ?: 3.0) as BigDecimal
    String during = am ? "06:00–10:00" : "16:00–20:00"
    if (advice == "keep") {
        BigDecimal deep = target - floorKWh > 40 && target > capped ? capped - floorKWh : target - floorKWh
        return "<div class='p-message p-message-warn p-2 mb-2 border-round'>" +
            tr("advice.keep", [use: kwh(w.currentValue('keepUse')), during: during, floor: kwh(floorKWh), deep: kwh(deep)]) + "</div>"
    }
    boolean limited = target - floorKWh > 40 && target > capped
    BigDecimal c = limited ? capped - floorKWh : target - floorKWh
    return "<div class='p-message p-message-info p-2 mb-2 border-round'>" +
        tr(limited ? "advice.cutLimit" : "advice.cut", [during: during, floor: kwh(floorKWh), c: kwh(c)]) + "</div>"
}

// The reference energy without the limit, the Hilo app's "original consumption limit". It needs the anchor
// period's consumption: final once the period is over, a running value during it, unknown before.
private List referenceEnergyRow(ChildDeviceWrapper w, boolean am, boolean live, String anchor) {
    String label = "${tr('ref.label')}<small>${tr('ref.hilo')}</small>"
    BigDecimal base = w.currentValue("base") as BigDecimal, target = w.currentValue("target") as BigDecimal
    if (target != null) return [label, "<b>${kwh(target)}</b> kWh", tr("ref.parts", [base: kwh(base), adj: kwh(target - base)])]
    String end = am ? "04:00" : "14:00"
    BigDecimal used = w.currentValue("anchorUsed") as BigDecimal, ref = w.currentValue("anchorReference") as BigDecimal
    if (live && used != null && base != null && ref != null) {
        BigDecimal soFar = base + (am ? 4.0 / 3.0 : 2.0) * (used - ref)
        return [label, tr("ref.soFar", [v: kwh(soFar)]), tr("ref.soFarNote", [end: end])]
    }
    return [label, tr("ref.knownAt", [end: end]), tr("ref.knownAtNote", [base: kwh(base), anchor: anchor])]
}

void installed() { logDebug "installed"; state.version = CODE_VERSION; initialize() }

void updated() {
    logDebug "updated"
    unsubscribe()
    unschedule()
    // unschedule() drops a pending backfill step; let the next poll resume it.
    state.remove("historyBusy")
    initialize()
    if (settings.debugEnable || settings.traceEnable) runIn(DEBUG_LOG_TIMEOUT, "logsOff")
}

void uninstalled() {
    logDebug "uninstalled"
    ACCESS.remove(app.id.toString())
    dropSecret("refreshTokenEnc")
}

void initialize() {
    logDebug "initialize"
    checkVersion(false)
    if (!isLoggedIn() || !state.locationId) return
    // Without its device the instance doesn't poll: another instance may own the location.
    if (!ensureChild()) return
    schedulePolling()
    runIn(5, "poll")
    if (settings.wcEnable) peakInitialize()
}

// A code push doesn't run updated(); entry points call this so the first one after a
// push converges as if Done were pressed.
private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "new version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void appButtonHandler(String btn) {
    logDebug "appButtonHandler(${btn})"
    checkVersion()
    switch (btn) {
        case "btnLogout":     logout(); break
        case "btnPeakNow":    peakRun(true); break
        default: logWarn "unhandled button: ${btn}"
    }
}

// ── Login ─────────────────────────────────────────────────────────────

private boolean isLoggedIn() { return state.refreshTokenEnc != null }

private void newPkce() {
    String seed = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
    state.codeVerifier = base64UrlEncode(seed.getBytes("UTF-8"))
    state.oauthState = UUID.randomUUID().toString()
}

private String authorizeUrl() {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest((state.codeVerifier as String).getBytes("US-ASCII"))
    String qs = toQueryString([
        client_id            : CLIENT_ID,
        response_type        : "code",
        redirect_uri         : REDIRECT_URI,
        scope                : SCOPE,
        code_challenge       : base64UrlEncode(digest),
        code_challenge_method: "S256",
        state                : state.oauthState
    ])
    return "${AUTH_URL}authorize?${qs}"
}

// Runs inside the page render: the page needs the result before it returns.
private void redeemPastedUrl(String pasted) {
    state.remove("loginError")
    Map<String, String> q = queryParams(pasted)
    if (q.error) { state.loginError = [key: "err.refused", args: [why: q.error_description ?: q.error]]; return }
    if (!q.code) { state.loginError = [key: "err.noCode"]; return }
    if (q.state != state.oauthState) { state.loginError = [key: "err.oldLink"]; newPkce(); return }
    String verifier = state.codeVerifier as String
    // A code is redeemed once; the next login gets a fresh verifier.
    newPkce()
    Map tokens = tokenRequest([grant_type: "authorization_code", client_id: CLIENT_ID, code: q.code,
                               redirect_uri: REDIRECT_URI, code_verifier: verifier])
    if (tokens.error) { state.loginError = [key: "err.loginFailed", args: [why: tokens.error]]; return }
    storeTokens(tokens)
    logCfg "logged in to Hilo"
    loadLocations()
}

private void loadLocations() {
    List<Map> locs = []
    try {
        httpGet([uri: "${API_URL}/Locations", headers: apiHeaders(hiloAccessToken()), contentType: "application/json", timeout: HTTP_TIMEOUT]) { resp ->
            (resp.data as List).each { Map l -> locs << [id: l.id as String, hiloId: l.locationHiloId as String, name: (l.name ?: "Location ${l.id}") as String] }
        }
    } catch (Exception e) {
        state.loginError = [key: "err.locations", args: [why: exceptionText(e)]]
        return
    }
    state.locations = locs
    logDebug "locations: ${locs.collect { it.name }}"
    if (locs.size() == 1) {
        app.updateSetting("locationId", [type: "enum", value: locs[0].id])
        selectLocation(locs[0].id as String)
    }
}

private void selectLocation(String id) {
    Map loc = ((state.locations ?: []) as List<Map>).find { it.id == id }
    if (!loc) return
    state.locationId = loc.id
    state.locationHiloId = loc.hiloId
    state.locationName = loc.name
    if (!app.label || app.label == "Hilo Manager") app.updateLabel("Hilo Manager - ${loc.name}")
    logCfg "location: ${loc.name}"
    // Creates the device and starts polling without waiting for Done.
    state.childRequestedAt = now()
    runIn(1, "updated")
}

void logout() {
    logCfg "logged out of Hilo"
    unschedule()
    ACCESS.remove(app.id.toString())
    dropSecret("refreshTokenEnc")
    ["refreshTokenEnc", "locations", "locationId", "locationHiloId", "locationName", "pollStreak", "childError", "childRequestedAt",
     "historyStart", "lastCompleteDay", "historyBusy", "backfilling", "finalDay", "lastHourMs",
     "todayHours", "yesterdayHours", "peakEvents", "peakLast", "anchorLive", "uiLangSynced"].each { state.remove(it) }
    app.removeSetting("locationId")
    meterChild()?.stopLiveStream()
    meterChild()?.updateHealth("offline", tr("health.loggedOut", [:], "en"), tr("health.loggedOut"))
}

// ── Tokens ────────────────────────────────────────────────────────────

// Called by the child before each stream connect, and by the polls. Returns null when
// no token can be had; the reason is already logged.
String hiloAccessToken() {
    List cached = ACCESS.get(app.id.toString())
    if (cached && (cached[1] as long) - now() > TOKEN_REFRESH_BUFFER_MS) return cached[0] as String
    String refresh = decryptState("refreshTokenEnc")
    if (!refresh) return null
    Map tokens = tokenRequest([grant_type: "refresh_token", client_id: CLIENT_ID, refresh_token: refresh, scope: SCOPE])
    if (tokens.error) {
        if (tokens.rejected) loginLost(tokens.error as String)
        else logWarn "token refresh failed: ${tokens.error}"
        return null
    }
    storeTokens(tokens)
    return tokens.access_token as String
}

// Sync: the caller needs the token before it can do anything else.
// Returns the token response, or [error: text, rejected: true when Hilo refused the grant].
private Map tokenRequest(Map body) {
    Map out = [:]
    try {
        httpPost([uri: "${AUTH_URL}token", requestContentType: "application/x-www-form-urlencoded", contentType: "application/json",
                  headers: ["User-Agent": USER_AGENT], body: body, timeout: HTTP_TIMEOUT]) { resp -> out = resp.data as Map }
    } catch (groovyx.net.http.HttpResponseException e) {
        Map err = [:]
        try { err = e.response?.data as Map } catch (Exception ignored) { }
        int status = e.statusCode
        return [error: "HTTP ${status}: ${err?.error_description ?: err?.error ?: e.message}", rejected: status == 400 || status == 401]
    } catch (Exception e) {
        return [error: exceptionText(e)]
    }
    if (!out.access_token) return [error: "no access token in Hilo's answer"]
    return out
}

// Hilo issues a new refresh token on every refresh, so each one replaces the last.
private void storeTokens(Map t) {
    long expiresIn = ((t.expires_in ?: 3600) as long) * 1000L
    ACCESS.put(app.id.toString(), [t.access_token as String, now() + expiresIn])
    if (t.refresh_token) {
        state.refreshTokenEnc = encrypt(t.refresh_token as String)
        SECRET_CACHE.put("${app.id}:refreshTokenEnc".toString(), [state.refreshTokenEnc as String, t.refresh_token as String])
    }
    state.remove("loginError")
}

private void loginLost(String why) {
    logError "Hilo refused the saved login (${why}); log in again from the app page"
    unschedule("poll")
    ACCESS.remove(app.id.toString())
    dropSecret("refreshTokenEnc")
    state.remove("refreshTokenEnc")
    state.loginError = [key: "err.loginLost"]
    meterChild()?.updateHealth("offline", tr("health.loginAgain", [:], "en"), tr("health.loginAgain"))
}

private String decryptState(String key) {
    String enc = state[key] as String
    if (!enc) return null
    List<String> hit = SECRET_CACHE.get("${app.id}:${key}".toString())
    if (hit != null && hit[0] == enc) return hit[1]
    try {
        String plain = decrypt(enc)
        SECRET_CACHE.put("${app.id}:${key}".toString(), [enc, plain])
        return plain
    } catch (Exception e) {
        logWarn "could not decrypt ${key} (${e.message}); log in again"
        state.remove(key)
        dropSecret(key)
        return null
    }
}

private void dropSecret(String key) { SECRET_CACHE.remove("${app.id}:${key}".toString()) }

private Map<String, String> apiHeaders(String token) {
    return ["Authorization": "Bearer ${token}", "Ocp-Apim-Subscription-Key": SUBSCRIPTION_KEY,
            "Hilo-Tenant": "hilo", "User-Agent": USER_AGENT]
}

// ── Child device ──────────────────────────────────────────────────────

private String meterDni() { return "${DNI_PREFIX}${state.locationHiloId}-energy" }

private ChildDeviceWrapper meterChild() { return state.locationHiloId ? getChildDevice(meterDni()) : null }

private boolean ensureChild() {
    ChildDeviceWrapper meter = meterChild()
    if (!meter) {
        try {
            meter = addChildDevice("iamtrep", DRIVER_METER, meterDni(), [name: DRIVER_METER, label: "Hilo ${state.locationName}"])
            logCfg "created ${meter.displayName}"
            state.remove("childError")
        } catch (Exception e) {
            // DNIs are hub-wide: the usual cause is another instance serving this location.
            state.childError = [key: "err.child", args: [why: e.message]]
            logError tr("err.child", [why: e.message], "en")
            return false
        }
    }
    meter.updateDataValue("locationHiloId", state.locationHiloId as String)
    meter.setLanguage(uiLang())
    meter.startLiveStream()
    return true
}

// Called by the child's refresh().
void refreshNow() { checkVersion(); poll() }

// ── History polling ───────────────────────────────────────────────────

// Hourly at a random minute from :10 to :50, so hubs don't call Hilo in step.
private void schedulePolling() {
    Random rng = new Random()
    schedule("${rng.nextInt(60)} ${10 + rng.nextInt(41)} * ? * *", "poll")
}

void poll() {
    checkVersion()
    if (!isLoggedIn() || !state.locationId) return
    if (state.pausedUntil && now() < (state.pausedUntil as long)) { logDebug "paused by Retry-After"; return }
    String token = hiloAccessToken()
    if (!token) { if (isLoggedIn()) pollFailed("no access token"); return }
    Date nowDate = new Date()
    String today = nowDate.format("yyyy-MM-dd", location.timeZone)
    String yesterday = (nowDate - 1).format("yyyy-MM-dd", location.timeZone)
    fetchDay(token, today)
    if (state.finalDay != yesterday) fetchDay(token, yesterday)
    continueHistory()
}

private void fetchDay(String token, String day) {
    logNet "Energy2 ${day}"
    asynchttpGet("energyCallback", [uri: "${API_URL}/Locations/${state.locationId}/History/Energy2",
                                    query: [date: day, timescale: "Day"], headers: apiHeaders(token),
                                    contentType: "application/json", timeout: HTTP_TIMEOUT], [day: day])
}

void energyCallback(resp, data) {
    String day = data.day as String
    if (resp.hasError() || resp.getStatus() != 200) {
        int status = resp.getStatus()
        if (status == 429 || status == 503) noteRetryAfter(resp)
        if (status == 401) ACCESS.remove(app.id.toString())
        pollFailed("Energy2 ${day}: ${httpError(resp)} ${bodyExcerpt(resp)}")
        return
    }
    List<Map> hours
    try {
        hours = parseHours(resp.getJson())
    } catch (Exception e) {
        pollFailed("Energy2 ${day}: unreadable answer (${e.message})")
        return
    }
    pollSucceeded()
    logTrace "Energy2 ${day}: ${hours}"
    Map ev = [:]
    String today = new Date().format("yyyy-MM-dd", location.timeZone)
    List<Map> known = hours.findAll { it.kWh != null }
    // The files hold complete days only; the target needs today's hours and an unfinished yesterday.
    List<Map> kept = hours.collect { [start: it.start, kWh: it.kWh == null ? null : round3(it.kWh as BigDecimal)] }
    if (day == today) state.todayHours = [day: day, hours: kept]
    else state.yesterdayHours = [day: day, hours: kept]
    if (day == today) {
        ev.energy = round3(known.sum(0.0) { it.kWh as BigDecimal } as BigDecimal)
    } else if (known && known.size() == hours.size()) {
        // Every hour in: yesterday is final and is not read again once it is on file.
        ev.energyYesterday = round3(known.sum(0.0) { it.kWh as BigDecimal } as BigDecimal)
        if (writeDay(day, hours)) {
            state.finalDay = day
            if (state.lastCompleteDay == LocalDate.parse(day).minusDays(1).toString()) state.lastCompleteDay = day
        }
    }
    Map last = known ? known.max { it.ms as long } : null
    if (last && (last.ms as long) > ((state.lastHourMs ?: 0L) as long)) {
        state.lastHourMs = last.ms
        ev.energyLastHour = round3(last.kWh as BigDecimal)
        ev.lastHourStart = last.start
    }
    state.lastPoll = now()
    meterChild()?.updateEnergy(ev)
    if (settings.wcEnable && day == today) runIn(5, "peakUpdate")
}

// Each hour keeps its own start time, so 23- and 25-hour days need no special case.
private List<Map> parseHours(json) {
    List vals = (json?.points?.values ?: []) as List
    return vals.collect { Map v ->
        String start = v.date as String
        [start: start, ms: OffsetDateTime.parse(start).toInstant().toEpochMilli(), kWh: v.energy == null ? null : (v.energy as BigDecimal)]
    }
}

// ── History files ─────────────────────────────────────────────────────
// One File Manager file per month: {unit, location, days: {"YYYY-MM-DD": [{start, kWh}, …]}}.
// state.historyStart is the first day Hilo has data for; state.lastCompleteDay is how far
// the files are complete. Yesterday is written by the poll once all its hours are in;
// older days are read here, one request at a time, and kept with any null hours.

private String historyFile(String month) { return "hilo-${state.locationId}-energy-${month}.json" }

private LocalDate localToday() { return LocalDate.now(location.timeZone.toZoneId()) }

private boolean historyBusy() {
    return state.historyBusy && now() - (state.historyBusy as long) < HISTORY_STALE_MS
}

private void continueHistory() {
    if (historyBusy()) return
    if (state.historyStart) { backfillNext(); return }
    state.historyBusy = now()
    logInfo "looking for the start of the Hilo history"
    fetchWeek(localToday().minusDays(6).toString(), 0, null)
}

// Steps back a week at a time until a week has no data at all. Week answers give 0.0, not
// null, for days before the data starts (Day answers give null), so 0 counts as no data.
private void fetchWeek(String start, int weeks, String laterFirst) {
    String token = hiloAccessToken()
    if (!token) { state.remove("historyBusy"); return }
    logNet "Energy2 week from ${start}"
    asynchttpGet("weekCallback", [uri: "${API_URL}/Locations/${state.locationId}/History/Energy2",
                                  query: [date: start, timescale: "Week"], headers: apiHeaders(token),
                                  contentType: "application/json", timeout: HTTP_TIMEOUT],
                 [start: start, weeks: weeks, laterFirst: laterFirst])
}

void weekCallback(resp, data) {
    if (resp.hasError() || resp.getStatus() != 200) {
        // The poll reports outages; the search resumes at the next poll.
        logDebug "history search stopped: ${httpError(resp)}"
        state.remove("historyBusy")
        return
    }
    String first
    try {
        Map hit = ((resp.getJson()?.points?.values ?: []) as List<Map>).find { it.energy != null && (it.energy as BigDecimal) > 0 }
        first = hit ? LocalDate.parse((hit.date as String).take(10)).toString() : null
    } catch (Exception e) {
        logDebug "history search stopped: unreadable answer (${e.message})"
        state.remove("historyBusy")
        return
    }
    int weeks = data.weeks as int
    String found = first == null ? data.laterFirst as String : (weeks >= HISTORY_MAX_WEEKS ? first : null)
    if (first == null && found == null) {
        logInfo "no Hilo history yet"
        state.remove("historyBusy")
        return
    }
    if (found) {
        state.historyStart = found
        state.lastCompleteDay = LocalDate.parse(found).minusDays(1).toString()
        long days = localToday().toEpochDay() - LocalDate.parse(found).toEpochDay()
        logInfo "Hilo history starts on ${found}; reading ${days} days into File Manager, one a minute"
        state.remove("historyBusy")
        backfillNext()
        return
    }
    fetchWeek(LocalDate.parse(data.start as String).minusDays(7).toString(), weeks + 1, first)
}

private void backfillNext() {
    String next = LocalDate.parse(state.lastCompleteDay as String).plusDays(1).toString()
    // Yesterday and today belong to the poll.
    if (LocalDate.parse(next) >= localToday().minusDays(1)) {
        if (state.backfilling) { logInfo "Hilo history complete in File Manager from ${state.historyStart}"; state.remove("backfilling") }
        state.remove("historyBusy")
        return
    }
    String token = hiloAccessToken()
    if (!token) { state.remove("historyBusy"); return }
    state.historyBusy = now()
    state.backfilling = true
    logNet "Energy2 ${next} (history)"
    asynchttpGet("backfillCallback", [uri: "${API_URL}/Locations/${state.locationId}/History/Energy2",
                                      query: [date: next, timescale: "Day"], headers: apiHeaders(token),
                                      contentType: "application/json", timeout: HTTP_TIMEOUT], [day: next])
}

void backfillTick() { backfillNext() }

void backfillCallback(resp, data) {
    String day = data.day as String
    if (resp.hasError() || resp.getStatus() != 200) {
        logDebug "history read of ${day} stopped: ${httpError(resp)}"
        state.remove("historyBusy")
        return
    }
    List<Map> hours
    try {
        hours = parseHours(resp.getJson())
    } catch (Exception e) {
        logWarn "history for ${day} unreadable (${e.message}); skipped"
        hours = null
    }
    if (hours != null && !writeDay(day, hours)) { state.remove("historyBusy"); return }
    state.lastCompleteDay = day
    // historyBusy stays set between days, so the hourly poll doesn't start a second chain.
    if (LocalDate.parse(day).plusDays(1) >= localToday().minusDays(1)) backfillNext()
    else runIn(BACKFILL_INTERVAL_SECS, "backfillTick")
}

// Rewrites the day's month file with that day replaced. Returns false when the file can't be written.
private boolean writeDay(String day, List<Map> hours) {
    String name = historyFile(day.take(7))
    Map doc = readHistoryFile(name)
    if (doc == null) return false
    Map days = (doc.days ?: [:]) as Map
    days[day] = hours.collect { [start: it.start, kWh: it.kWh == null ? null : round3(it.kWh as BigDecimal)] }
    doc.days = days.sort { it.key }
    try {
        uploadHubFile(name, JsonOutput.toJson(doc).getBytes("UTF-8"))
    } catch (Exception e) {
        logWarn "could not write ${name}: ${getObjectClassName(e)}: ${e.message}"
        return false
    }
    logDebug "wrote ${day} to ${name}"
    return true
}

// A new month starts empty. downloadHubFile returns null for a missing file from 2.5.3 and
// throws NoSuchFileException before. Returns null when an existing file can't be read,
// so it is never overwritten with less than it held.
private Map readHistoryFile(String name) {
    Map empty = [unit: "kWh", location: state.locationId, days: [:]]
    byte[] bytes
    try {
        bytes = downloadHubFile(name)
    } catch (Exception e) {
        if (getObjectClassName(e).endsWith("NoSuchFileException")) return empty
        logWarn "could not read ${name}: ${getObjectClassName(e)}: ${e.message}"
        return null
    }
    if (bytes == null) return empty
    try {
        return parseJson(new String(bytes, "UTF-8")) as Map
    } catch (Exception e) {
        logWarn "${name} is not valid JSON (${e.message}); left as it is"
        return null
    }
}

// The viewer pages are uploaded to File Manager by hand; see the README.
private boolean viewerInstalled(String name) {
    try { return downloadHubFile(name) != null } catch (Exception e) { return false }
}

@CompileStatic
private static BigDecimal round3(BigDecimal v) { return v.setScale(3, BigDecimal.ROUND_HALF_UP) }

// ── Winter credit target ──────────────────────────────────────────────
// Hydro-Québec Electricity Rates, chapter 2, article 2.59. The target (the Hilo app's
// "limite de consommation") is the base plus the temperature adjustment:
//  - base: for each event hour, the mean of that hour on the 5 previous days of the same kind
//    (weekday or weekend) with no event, without the lowest and highest value;
//  - adjustment: (use in the anchor window − the same mean over the anchor window) × event hours /
//    anchor hours. Anchor: 3 h from 5 h before a morning event, 2 h from 4 h before an evening one;
//  - above 40 kWh curtailed, the adjustment is the smaller of that and 2 × the anchor reference ×
//    the same proration (the "smaller of" wording is Hydro-Québec's clarification, Régie file
//    R-4307-2025, B-0194).
// Days without peak hours are skipped as reference days; this and the proration reproduce
// Hydro-Québec's targets for winter 2025-26 (see the README).
@Field static final BigDecimal CAP_KWH = 40.0
@Field static final int EVENT_HOURS = 4
// Credit per kWh curtailed and the energy price above 40 kWh a day, ¢ (Rates April 1, 2026, 2.64 and
// 2.5): weigh using energy during an event to keep 40 kWh against letting the cap apply.
@Field static final BigDecimal CREDIT_CENTS = 58.490
@Field static final BigDecimal ENERGY_CENTS = 11.142

// Easter Sunday, Gregorian (anonymous algorithm).
@CompileStatic
static LocalDate easterSunday(int year) {
    int a = year % 19, b = year.intdiv(100) as int, c = year % 100
    int d = b.intdiv(4) as int, e = b % 4
    int g = (8 * b + 13).intdiv(25) as int
    int h = (19 * a + b - d - g + 15) % 30
    int i = c.intdiv(4) as int, k = c % 4
    int l = (32 + 2 * e + 2 * i - h - k) % 7
    int m = (a + 11 * h + 22 * l).intdiv(451) as int
    int n = h + l - 7 * m + 114
    return LocalDate.of(year, n.intdiv(31) as int, (n % 31) + 1)
}

// Days with no peak hours in the winter around `day` (2.59): Dec 24, 25, 26, 31, Jan 1, 2,
// Good Friday and Easter Monday.
@CompileStatic
static Set<String> noPeakDays(LocalDate day) {
    int w = day.monthValue >= 7 ? day.year : day.year - 1
    LocalDate e = easterSunday(w + 1)
    return ([LocalDate.of(w, 12, 24), LocalDate.of(w, 12, 25), LocalDate.of(w, 12, 26), LocalDate.of(w, 12, 31),
             LocalDate.of(w + 1, 1, 1), LocalDate.of(w + 1, 1, 2), e.minusDays(2), e.plusDays(1)]
            .collect { it.toString() }) as Set<String>
}

/**
 * The target of a winter-credit event, computed from hourly kWh. Self-contained, for tests.
 * hours: "YYYY-MM-DD" -> local hour (0-23) -> kWh. day/startHour: the event (6 or 16).
 * eventDays: days with an event, never reference days. floorKWh: what the home uses in an event
 * with everything cut. anchorActual: the event day's anchor-window kWh, or null before it is known.
 * Returns refDays, skipped [[day, why]], rows (per hour: the five values, which were dropped, the
 * mean), base, anchorRef, k, cappedTarget, anchorPlan ("at most" / "at least"), anchorTarget,
 * expectedCut, missing (hours with no value) and, with anchorActual: target, cut (curtailment if
 * the event is cut to the floor), keepUse (event use that holds curtailment at 40, or null) and
 * advice ("cut" / "keep").
 */
@CompileStatic
static Map peakTarget(Map<String, Map<Integer, BigDecimal>> hours, String day, int startHour, Set<String> eventDays,
                      BigDecimal floorKWh, BigDecimal anchorActual) {
    LocalDate d0 = LocalDate.parse(day)
    boolean weekend = d0.dayOfWeek.value >= 6
    Set<String> noPeak = noPeakDays(d0)
    int back = startHour < 12 ? 5 : 4
    int alen = startHour < 12 ? 3 : 2
    BigDecimal k = (EVENT_HOURS as BigDecimal) / alen
    List<String> refs = []
    List<List<String>> skipped = []
    LocalDate d = d0
    for (int i = 0; i < 60 && refs.size() < 5; i++) {
        d = d.minusDays(1)
        String s = d.toString()
        if ((d.dayOfWeek.value >= 6) != weekend) continue
        if (noPeak.contains(s)) skipped << [s, "no peak hours"]
        else if (eventDays.contains(s)) skipped << [s, "event"]
        else refs << s
    }
    List<String> missing = []
    List<Map> rows = []
    BigDecimal base = 0.0, anchorRef = 0.0
    List<Integer> eventHrs = (startHour..<(startHour + EVENT_HOURS)).toList()
    List<Integer> anchorHrs = ((startHour - back)..<(startHour - back + alen)).toList()
    (anchorHrs + eventHrs).each { Integer h ->
        List<BigDecimal> vals = refs.collect { String r ->
            BigDecimal v = hours[r]?.get(h)
            if (v == null) missing << "${r} ${h}:00".toString()
            v
        }
        List<Integer> idx = (0..<vals.size()).findAll { vals[it as int] != null }
        Integer lo = null, hi = null
        if (idx.size() >= 3) {
            lo = idx.min { vals[it as int] }
            hi = idx.findAll { it != lo }.max { vals[it as int] }
        }
        List<BigDecimal> kept = idx.findAll { it != lo && it != hi }.collect { vals[it as int] }
        BigDecimal mean = kept ? (kept.sum() as BigDecimal) / kept.size() : 0.0
        if (eventHrs.contains(h)) base += mean else anchorRef += mean
        rows << [hour: h, anchor: anchorHrs.contains(h), values: vals, low: lo, high: hi, mean: mean]
    }
    BigDecimal capped = base + k * 2 * anchorRef
    Map out = [day: day, startHour: startHour, refDays: refs, skipped: skipped, rows: rows, missing: missing,
               anchorHours: anchorHrs, base: base, anchorRef: anchorRef, k: k, cappedTarget: capped, floor: floorKWh]
    if (capped - floorKWh >= CAP_KWH) {
        // Cutting deep earns at least 40 kWh; anchor use up to 3 × its reference raises it to the capped target.
        out.anchorPlan = "at least"
        out.anchorTarget = 3 * anchorRef
        out.expectedCut = capped - floorKWh
    } else {
        // The cap would cost: anchor use that puts the target at floor + 40, and no more.
        out.anchorPlan = "at most"
        out.anchorTarget = [(CAP_KWH + floorKWh - base) / k + anchorRef, 0.0].max()
        out.expectedCut = [base + k * ((out.anchorTarget as BigDecimal) - anchorRef) - floorKWh, CAP_KWH].min()
    }
    if (anchorActual != null) {
        BigDecimal target = base + k * (anchorActual - anchorRef)
        BigDecimal cut = target - floorKWh
        if (cut > CAP_KWH && target > capped) cut = capped - floorKWh
        out.target = target
        out.cut = [cut, 0.0].max()
        BigDecimal keep = target - CAP_KWH
        // Keeping 40 kWh pays when it beats the capped curtailment by more than the energy it uses.
        boolean keepPays = keep > floorKWh && CAP_KWH * CREDIT_CENTS - (keep - floorKWh) * ENERGY_CENTS > (out.cut as BigDecimal) * CREDIT_CENTS
        out.keepUse = keepPays ? keep : null
        out.advice = keepPays ? "keep" : "cut"
    }
    return out
}

// ── Winter credit: schedule, data and outputs ─────────────────────────
// Hydro-Québec's peak events come from its open data (the winter credit's offer, CPC-D), read
// twice a day. Each hourly read of today's consumption recomputes the targets of the next
// morning and evening events, announced or not, and writes them to File Manager for the viewer;
// the Hilo Winter Credit device shows the next one. During an anchor window the device counts
// the window's energy from the live power, so a rule can stop pre-heating at the plan's limit.

private void peakInitialize() {
    if (!ensurePeakChild()) return
    Random rng = new Random()
    // Events are announced before 15:00 the day before. One cron for both reads: a second
    // schedule() under the same handler would replace the first.
    schedule("${rng.nextInt(60)} ${15 + rng.nextInt(15)} 15,21 ? * *", "fetchPeakEvents")
    if (settings.wcNotifyTime) schedule(timeToday(settings.wcNotifyTime as String, location.timeZone), "peakEvening")
    else schedule("0 0 22 ? * *", "peakEvening")
    // A minute after each anchor window ends.
    schedule("0 1 4,14 ? * *", "peakAnchorEnded")
    subscribe(meterChild(), "power", "peakPowerHandler")
    runIn(10, "fetchPeakEvents")
}

private String peakDni() { return "${DNI_PREFIX}${state.locationHiloId}-wintercredit" }

private ChildDeviceWrapper peakChild() { return state.locationHiloId ? getChildDevice(peakDni()) : null }

private boolean ensurePeakChild() {
    if (peakChild()) return true
    try {
        ChildDeviceWrapper d = addChildDevice("iamtrep", DRIVER_PEAK, peakDni(), [name: DRIVER_PEAK, label: tr("dev.peakLabel", [name: state.locationName])])
        logCfg "created ${d.displayName}"
        d.setLanguage(uiLang())
        return true
    } catch (Exception e) {
        logError "could not create the winter credit device: ${e.message}"
        return false
    }
}

// Called by the child's refresh().
void peakRefresh() { checkVersion(); peakRun(true) }

void fetchPeakEvents() {
    String since = LocalDate.now(location.timeZone.toZoneId()).minusDays(PEAK_LOOKBACK_DAYS).toString()
    logNet "peak events since ${since}"
    asynchttpGet("peakEventsCallback", [uri: PEAK_EVENTS_URL, contentType: "application/json", timeout: HTTP_TIMEOUT,
                                        query: [refine: "offre:\"${PEAK_OFFER}\"", where: "datedebut >= '${since}'",
                                                order_by: "datedebut", limit: 100]])
}

void peakEventsCallback(resp, data) {
    if (resp.hasError() || resp.getStatus() != 200) {
        // The last list is kept; the next read tries again.
        logWarn "Hydro-Québec peak events unavailable: ${httpError(resp)}"
        return
    }
    List<Map> evs
    try {
        evs = ((resp.getJson()?.results ?: []) as List<Map>).collect { Map r ->
            [s: OffsetDateTime.parse(r.datedebut as String).toInstant().toEpochMilli(),
             e: OffsetDateTime.parse(r.datefin as String).toInstant().toEpochMilli()]
        }
    } catch (Exception e) {
        logWarn "Hydro-Québec peak events unreadable: ${e.message}"
        return
    }
    List<Map> before = (state.peakEvents ?: []) as List<Map>
    state.peakEvents = evs
    long t = now()
    evs.findAll { (it.s as long) > t && !before.any { b -> b.s == it.s } }.each {
        logInfo "peak event announced: ${formatClock(it.s as long)} to ${new Date(it.e as long).format('HH:mm', location.timeZone)}"
    }
    peakRun(false)
}

// [day "YYYY-MM-DD", start hour] of every event in the list and in the extra-events setting.
private List<List> peakEventSlots() {
    List<List> out = ((state.peakEvents ?: []) as List<Map>).collect { Map ev ->
        Date d = new Date(ev.s as long)
        [d.format("yyyy-MM-dd", location.timeZone), d.format("H", location.timeZone) as int]
    }
    ((settings.wcExtraEvents ?: "") as String).split(",").each { String x ->
        def m = x.trim() =~ /^(\d{4}-\d{2}-\d{2})\s*(AM|PM)$/
        if (m.matches()) out << [m.group(1), m.group(2) == "AM" ? 6 : 16]
        else if (x.trim()) logWarn "extra event not understood: '${x.trim()}' (use YYYY-MM-DD AM or PM)"
    }
    return out.unique()
}

// Hourly kWh by day and local hour, from the month files plus the hours kept from today's reads.
private Map<String, Map<Integer, BigDecimal>> peakHours(LocalDate from, LocalDate to) {
    Map<String, Map<Integer, BigDecimal>> out = [:]
    Closure add = { String day, List<Map> hrs ->
        Map<Integer, BigDecimal> m = [:]
        hrs.each { Map h ->
            if (h.kWh == null) return
            int hr = (h.start as String).substring(11, 13) as int
            m[hr] = (m[hr] ?: 0.0) + (h.kWh as BigDecimal)
        }
        out[day] = m
    }
    for (LocalDate m = from.withDayOfMonth(1); m <= to; m = m.plusMonths(1)) {
        Map doc = readHistoryFile(historyFile(m.toString().take(7)))
        ((doc?.days ?: [:]) as Map).each { String day, List hrs -> if (day >= from.toString() && day <= to.toString()) add(day, hrs as List<Map>) }
    }
    [state.yesterdayHours, state.todayHours].each { Map kept ->
        if (kept?.day && !out.containsKey(kept.day as String)) add(kept.day as String, kept.hours as List<Map>)
    }
    return out
}

private static boolean inWinter(LocalDate d) { return d.monthValue == 12 || d.monthValue <= 3 }

// After each hourly read of today.
void peakUpdate() { peakRun(false) }

// Recomputes the next morning and evening targets. Outside December to March only on request.
void peakRun(boolean force) {
    if (!settings.wcEnable || !state.locationId) return
    LocalDate today = LocalDate.now(location.timeZone.toZoneId())
    LocalDate tomorrow = today.plusDays(1)
    if (!force && !inWinter(tomorrow) && !inWinter(today)) return
    List<List> slots = peakEventSlots()
    Set<String> eventDays = slots.collect { it[0] as String } as Set
    int nowHour = new Date().format("H", location.timeZone) as int
    // The windows still ahead: today's evening before 16:00 and tomorrow's two.
    List<List> windows = []
    if (nowHour < 6) windows << [today.toString(), 6]
    if (nowHour < 16) windows << [today.toString(), 16]
    windows << [tomorrow.toString(), 6]
    windows << [tomorrow.toString(), 16]
    Map<String, Map<Integer, BigDecimal>> hours = peakHours(today.minusDays(PEAK_LOOKBACK_DAYS), today)
    BigDecimal floorKWh = (settings.wcFloor ?: 3.0) as BigDecimal
    List<Map> results = windows.collect { List w ->
        String day = w[0] as String
        int h = w[1] as int
        Map r = peakTarget(hours, day, h, eventDays.findAll { it != day } as Set<String>, floorKWh, peakAnchorActual(day, h, hours))
        r.event = slots.any { it[0] == day && it[1] == h }
        r
    }
    // The device shows the next announced event, else tomorrow morning's simulation.
    Map next = results.find { it.event } ?: results.find { it.day == tomorrow.toString() && it.startHour == 6 }
    state.peakLast = [at: now(), next: peakBrief(next)]
    peakChild()?.updatePeak(peakAttributes(next) + [summaryLog: peakBrief(next)?.message])
    writePeakFile(results, floorKWh)
}

// The event day's anchor-window kWh once the window is over: Hilo's hourly values when all are in,
// else the live count. Null before the window ends.
private BigDecimal peakAnchorActual(String day, int startHour, Map<String, Map<Integer, BigDecimal>> hours) {
    int back = startHour < 12 ? 5 : 4, alen = startHour < 12 ? 3 : 2
    long end = timeOf(day, startHour - back + alen)
    if (now() < end) return null
    List<BigDecimal> vals = ((startHour - back)..<(startHour - back + alen)).collect { hours[day]?.get(it) }
    if (vals.every { it != null }) return vals.sum() as BigDecimal
    Map live = state.anchorLive as Map
    if (live?.day == day && live?.startHour == startHour) return (live.kWh as BigDecimal)
    return null
}

private long timeOf(String day, int hour) {
    return Date.parse("yyyy-MM-dd H", "${day} ${hour}", location.timeZone).time
}

// Live count of the anchor window's energy, from the meter's power (held until the next reading).
void peakPowerHandler(evt) {
    if (!settings.wcEnable) return
    Date t = new Date()
    int h = t.format("H", location.timeZone) as int
    Integer startHour = (h >= 1 && h < 4) ? 6 : ((h >= 12 && h < 14) ? 16 : null)
    String day = t.format("yyyy-MM-dd", location.timeZone)
    Map live = (state.anchorLive ?: [:]) as Map
    if (startHour == null) {
        if (live.open) { live.open = false; state.anchorLive = live }
        return
    }
    BigDecimal w = (evt.value ?: 0) as BigDecimal
    if (!(live.open && live.day == day && live.startHour == startHour)) {
        // sentKWh below zero, so the new window's 0 is sent at once.
        live = [day: day, startHour: startHour, open: true, kWh: 0.0, lastMs: t.time, lastW: w, sentKWh: -1.0]
    } else {
        long dt = t.time - (live.lastMs as long)
        // A gap over 10 min in the stream counts nothing rather than guess.
        if (dt > 0 && dt < 600_000L) live.kWh = (live.kWh as BigDecimal) + (live.lastW as BigDecimal) * dt / 3_600_000_000.0
        live.lastMs = t.time
        live.lastW = w
    }
    state.anchorLive = live
    BigDecimal used = round3(live.kWh as BigDecimal)
    if (used - (live.sentKWh as BigDecimal) >= 0.1) {
        live.sentKWh = used
        state.anchorLive = live
        peakChild()?.updatePeak([anchorUsed: used, anchorWindow: "${day} ${startHour == 6 ? '01:00-04:00' : '12:00-14:00'}"])
    }
}

// After an anchor window: recompute and, on an event day, say what the event needs.
void peakAnchorEnded() {
    if (!settings.wcEnable) return
    peakRun(false)
    Date t = new Date()
    String day = t.format("yyyy-MM-dd", location.timeZone)
    int startHour = (t.format("H", location.timeZone) as int) < 12 ? 6 : 16
    if (!peakEventSlots().any { it[0] == day && it[1] == startHour }) return
    Map next = (state.peakLast as Map)?.next as Map
    if (next?.day == day && next?.startHour == startHour && next?.message) peakNotify(next)
}

void peakEvening() {
    if (!settings.wcEnable) return
    peakRun(false)
    LocalDate tomorrow = LocalDate.now(location.timeZone.toZoneId()).plusDays(1)
    List<List> slots = peakEventSlots().findAll { it[0] == tomorrow.toString() }
    if (!slots && !(settings.wcNotifyDaily && inWinter(tomorrow))) return
    Map next = (state.peakLast as Map)?.next as Map
    if (next?.message) peakNotify(next)
}

// Logged in English; sent in the page's language (notice), or in English when computed before
// the page was bilingual.
private void peakNotify(Map next) {
    logInfo next.message as String
    String msg = (next.notice ?: next.message) as String
    ((settings.wcNotify ?: []) as List).each { it.deviceNotification(msg) }
}

// The fields the device and the notifications use, plus the one-paragraph message in English
// (message, for the log) and in the page's language (notice).
private Map peakBrief(Map r) {
    if (!r) return null
    return [day: r.day, startHour: r.startHour, event: r.event, plan: r.anchorPlan, anchorTarget: r.anchorTarget,
            message: peakMessage(r, "en"), notice: peakMessage(r, uiLang())]
}

private String peakMessage(Map r, String lang) {
    String window = r.startHour == 6 ? "06:00–10:00" : "16:00–20:00"
    String anchor = r.startHour == 6 ? "01:00–04:00" : "12:00–14:00", end = r.startHour == 6 ? "04:00" : "14:00"
    Closure n = { v -> kwh(v, 1, lang) }
    List parts = [tr(r.event ? "notif.head" : "notif.headIf", [day: r.day, window: window], lang),
                  tr("notif.capped", [capped: n(r.cappedTarget)], lang),
                  r.anchorPlan == "at most" ? tr("notif.atMost", [target: n(r.anchorTarget), anchor: anchor, floor: n(r.floor)], lang) :
                      tr("notif.atLeast", [target: n(r.anchorTarget), anchor: anchor, cut: n(r.expectedCut)], lang)]
    if (r.target != null) {
        parts << (r.advice == "keep" ?
            tr("notif.afterKeep", [end: end, target: n(r.target), use: n(r.keepUse), floor: n(r.floor), cut: n(r.cut)], lang) :
            tr("notif.afterCut", [end: end, target: n(r.target), floor: n(r.floor), cut: n(r.cut)], lang))
    }
    if (r.missing) parts << tr("notif.missing", [n: (r.missing as List).size()], lang)
    return parts.join(" ")
}

private Map peakAttributes(Map r) {
    if (!r) return [:]
    Map a = [eventDate: r.day, window: r.startHour == 6 ? "AM" : "PM", announced: r.event ? "true" : "false",
             base: round3(r.base as BigDecimal), anchorReference: round3(r.anchorRef as BigDecimal),
             cappedTarget: round3(r.cappedTarget as BigDecimal), anchorPlan: r.anchorPlan,
             anchorTarget: round3(r.anchorTarget as BigDecimal), expectedCurtailment: round3(r.expectedCut as BigDecimal),
             referenceDays: (r.refDays as List).join(", "), summary: peakBrief(r).notice]
    a.target = r.target == null ? null : round3(r.target as BigDecimal)
    a.advice = r.advice ?: "pending"
    a.keepUse = r.keepUse == null ? null : round3(r.keepUse as BigDecimal)
    return a
}


// File Manager copy of the last computation, inputs included, for the history viewer.
private void writePeakFile(List<Map> results, BigDecimal floorKWh) {
    // events: every event day and start hour the computation knew, extra ones included.
    Map doc = [generated: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone), floor: floorKWh,
               offer: PEAK_OFFER, events: peakEventSlots(), results: results]
    try {
        uploadHubFile(peakFile(), JsonOutput.toJson(doc).getBytes("UTF-8"))
    } catch (Exception e) {
        logWarn "could not write ${peakFile()}: ${getObjectClassName(e)}: ${e.message}"
    }
}

private String peakFile() { return "hilo-${state.locationId}-peak-target.json" }

// ── Outages ───────────────────────────────────────────────────────────

private void pollFailed(String msg) {
    Map streak = streakFailed((state.pollStreak ?: [:]) as Map, "Hilo", msg)
    state.pollStreak = streak
    if ((streak.count as int) == FAILURE_ERROR_THRESHOLD) meterChild()?.updateHealth("offline", tr("health.unreachable", [:], "en"), tr("health.unreachable"))
}

private void pollSucceeded() {
    if (!state.pollStreak) return
    Map streak = state.pollStreak as Map
    String recovery = streakRecovered(streak, "Hilo")
    state.remove("pollStreak")
    if (recovery) meterChild()?.updateHealth("online", recovery,
        tr("health.back", [d: formatDuration(now() - (streak.since as long)), n: streak.count]))
}

// Logs one failure of a streak and returns the updated streak [count, since, reminded]:
// warn below FAILURE_ERROR_THRESHOLD, one error at it, then a warn every OUTAGE_REMINDER_MS.
private Map streakFailed(Map streak, String what, String msg) {
    long t = now()
    int n = ((streak.count ?: 0) as int) + 1
    long since = (streak.since ?: t) as long
    Map updated = [count: n, since: since, reminded: streak.reminded]
    if (n < FAILURE_ERROR_THRESHOLD) {
        logWarn "${msg} (failure ${n}/${FAILURE_ERROR_THRESHOLD})"
    } else if (n == FAILURE_ERROR_THRESHOLD) {
        logError "${what} unreachable since ${formatClock(since)}: ${msg}"
        updated.reminded = t
    } else if (t - ((streak.reminded ?: 0L) as long) >= OUTAGE_REMINDER_MS) {
        logWarn "${what} still unreachable after ${formatDuration(t - since)} (${n} failures): ${msg}"
        updated.reminded = t
    } else {
        logDebug "${msg} (failure ${n})"
    }
    return updated
}

// Returns the recovery line when the streak had reached the threshold, else null.
private String streakRecovered(Map streak, String what) {
    int n = (streak.count ?: 0) as int
    if (n >= FAILURE_ERROR_THRESHOLD) {
        String recovery = "${what} back after ${formatDuration(now() - (streak.since as long))} (${n} failures)"
        logInfo recovery
        return recovery
    }
    if (n > 0) logDebug "${what} recovered after ${n} failure(s)"
    return null
}

// Honors a Retry-After in seconds by skipping polls until then.
private void noteRetryAfter(resp) {
    Long secs = null
    try {
        String v = resp.getHeaders()?.find { k, val -> (k as String)?.equalsIgnoreCase("Retry-After") }?.value as String
        if (v?.trim()?.isLong()) secs = v.trim().toLong()
    } catch (Exception e) {
        return
    }
    if (secs == null || secs <= 0) return
    long ms = Math.min(secs * 1000L, RETRY_AFTER_MAX_MS)
    state.pausedUntil = now() + ms
    logWarn "Hilo asked to retry after ${secs} s; polling paused until ${formatClock(now() + ms)}"
}

// ── Helpers ───────────────────────────────────────────────────────────

// getErrorMessage() and getErrorData() throw on a success, getData() on an error.
private String httpError(resp) {
    return resp.hasError() ? "HTTP ${resp.getStatus()}: ${resp.getErrorMessage()}" : "HTTP ${resp.getStatus()}"
}

private String bodyExcerpt(resp) {
    String body
    try { body = (resp.hasError() ? resp.getErrorData() : resp.getData())?.toString() ?: "" }
    catch (Exception e) { return "" }
    return body.length() > LOG_BODY_MAX ? body.take(LOG_BODY_MAX) + "…" : body
}

private String exceptionText(Exception e) {
    if (e instanceof groovyx.net.http.HttpResponseException) return "HTTP ${e.statusCode}: ${e.message}"
    return "${getObjectClassName(e)}: ${e.message}"
}

private Map<String, String> queryParams(String url) {
    int q = url.indexOf("?")
    if (q < 0) return [:]
    String qs = url.substring(q + 1)
    int hash = qs.indexOf("#")
    if (hash >= 0) qs = qs.substring(0, hash)
    return qs.split("&").findAll { it.contains("=") }.collectEntries { String p ->
        int i = p.indexOf("=")
        [(URLDecoder.decode(p.substring(0, i), "UTF-8")): URLDecoder.decode(p.substring(i + 1).trim(), "UTF-8")]
    }
}

@CompileStatic
private static String base64UrlEncode(byte[] data) {
    return data.encodeBase64().toString().replace("=", "").replace("+", "-").replace("/", "_")
}

@CompileStatic
private static String toQueryString(Map params) {
    return params.collect { k, v -> "${URLEncoder.encode(k.toString(), 'UTF-8')}=${URLEncoder.encode(v.toString(), 'UTF-8').replace('+', '%20')}" }.join("&")
}

private String formatClock(long t) {
    return new Date(t).format("yyyy-MM-dd HH:mm", location.timeZone)
}

private static String formatDuration(long ms) {
    long minutes = ms.intdiv(60000L)
    if (minutes < 60) return "${minutes} min"
    return "${minutes.intdiv(60L)} h ${minutes % 60} min"
}

void logsOff() {
    logWarn "debug and trace logging disabled"
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
}

// ── Logging (app) ─────────────────────────────────────────────────────
//   🔧 Cfg  🌐 Net  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (settings.debugEnable) log.debug logp('🌐') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (settings.traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (settings.debugEnable) log.debug "${app.getLabel()}: ${m}" }
