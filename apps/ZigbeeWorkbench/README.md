<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Zigbee Workbench

Inspect and change any Zigbee device on the hub without changing its driver.

Zigbee Workbench reads a device's network tables, descriptors and attributes, writes attributes, configures reporting, sends cluster and raw commands, binds and unbinds clusters, and can make a device leave the network or look for a firmware update. It shows the decoded results on the app's pages. Tools such as Gary Milne's Zigbee Monitor Driver and Dan Danache's Knockturn Alley have to be swapped onto the device as its driver. A driver change clears the device's `state` and scheduled jobs, and can break app links and HomeKit. Zigbee Workbench leaves the device's driver, settings, state and data alone: it sends its requests from its own radio device and reads the replies off the hub's Zigbee log. The device's driver still receives those replies, as it receives every frame from the device, so a driver that records when it last heard from the device updates that time.

Optional monitor devices publish a device's health and a few mesh values for dashboards and rules. A shareable report gathers what a forum thread needs about one device, with addresses and names masked.

## Files

| File | Type | Name on the hub |
|---|---|---|
| `ZigbeeWorkbench.groovy` | App | Zigbee Workbench |
| `ZigbeeWorkbenchRadio.groovy` | Driver | Zigbee Workbench Radio |
| `ZigbeeWorkbenchMonitor.groovy` | Driver | Zigbee Workbench Monitor (optional) |

## Install

1. Add the app in **Apps Code** and the radio driver in **Drivers Code**. Add the monitor driver too if you want monitor devices.
2. In **Apps**, choose **Add user app** and pick **Zigbee Workbench**.
3. Pick the devices to inspect and press **Done**. Saving creates the **Zigbee Workbench Radio** device, which belongs to the app and is deleted with it.

## Pages

- **Main page:** the device picker, then one line per picked device with its IEEE address, its current short address, its type (once the node descriptor has been read) and the time of its last result. A picked device that is not a Zigbee device is listed as such. Each line opens the device page. Under **Monitor devices**: how often monitored devices are polled (15, 30, 60 or 180 minutes; 60 by default), and any monitor device whose device is no longer picked, with a **Remove** button. The **Hub Zigbee tables** link opens the hub page.
- **Device page:** a button per request, with the result under it as a table. The forms below Attributes change the device (all but Read Reporting Configuration) and ask for confirmation first. Device names in the tables come from the hub's Zigbee details. While a request waits for its reply the page refreshes every 2 seconds.
  - Health and monitor device: the device's health (`online` when the hub heard from it within the offline limit, `offline` after it, `unknown` when the hub has no time for it), the time and count of its messages from the hub's Zigbee details, and, once the neighbour table has been read, its neighbour and child counts, parent, lowest-LQI neighbour and active routes. **Monitor this device** creates its monitor device; **Poll now** polls it; **Stop monitoring…** removes the monitor device after confirmation. The offline limit defaults to 60 minutes for a mains-powered device and 720 for a battery one (from the power flag in the node descriptor, which a poll reads; 60 until then) and can be changed per device.
  - Interview: **Interview** reads the node descriptor, the active endpoints, the simple descriptor of each endpoint, then discovers and reads every attribute of each server cluster (endpoints `01`-`F0`), one request at a time; nothing is written. A step with no reply or an error is noted in the report and the interview goes on, except when the Zigbee log connection is lost. The report is plain text to copy: manufacturer, model, node type, endpoints, then per cluster each attribute with its name, ZCL type and value. Names cover 16 common clusters (from ZCL rev 8); other clusters and attributes show as hex. Manufacturer-specific attributes are not discovered.
  - Shareable report: **Create report** runs the interview (unless one finished without errors in the last 10 minutes), the neighbour, routing (routers only) and binding tables not read in that time, and Read Reporting Configuration for the attributes of each server cluster except Basic, Identify, Groups, Scenes and OTA. It then shows a Markdown version for a forum post in a text box with a **Copy** button, and saves the full version to File Manager as `zigbee-workbench-<model>-<yyyyMMdd-HHmm>.md`, keeping the newest 3 per model (the device's name is never in the file name). The report holds named fields only: the device's identity and firmware, its driver, endpoints, attributes with their reporting configuration, bindings, its neighbours and routes, its message count and last-hop LQI, the hub's model, firmware and channel, and the run's failures with their request frame and reply. IEEE addresses keep their first 3 bytes (`282C02XXXXXXXXXX`), also inside frames, and extended PAN IDs are masked. With **Replace device names with aliases** on (the default), every device name becomes the alias Hub Inspector gives it on the same hub; a name it would not alias shows as `unlisted device`. The forum version stays under 30,000 characters: it drops attribute values first, then the attribute list, and says so.
  - Network tables: neighbours (Mgmt_Lqi), routes (Mgmt_Rtg), bindings (Mgmt_Bind). Each table is read page by page until it is complete.
  - Descriptors: node, power, active endpoints, and the simple descriptor of each endpoint.
  - Attributes: Read Attributes and Discover Attributes on an endpoint and cluster typed in hex (defaults: the device's endpoint, cluster `0000`, attributes `0004,0005`).
  - Write and reporting: Write Attributes (attribute, ZCL type and value), Configure Reporting (minimum and maximum interval in seconds, and a reportable change for numeric types) and Read Reporting Configuration, on an endpoint (`01`-`F0`) and cluster typed in hex, with an optional manufacturer code. Values are typed in hex, most significant byte first; the app puts them in the order the device expects, and adds the length byte to strings. A write is reported with the device's status for it, such as `READ_ONLY` or `UNSUPPORTED_ATTRIBUTE`.
  - Cluster command: command id, payload bytes, cluster-specific or general, direction, and whether to disable the default response. With the default response disabled, a command that succeeds may get no reply at all, and is then reported as a timeout that says so. A cluster-specific answer is recognised only by its cluster, endpoint and direction, so the page says it may not be the reply; the result keeps the endpoint it came from and when it arrived.
  - Raw command: one `he` command (`he raw`, `he cmd`, `he rattr`, `he wattr` or `he cr`), sent as typed. It must be addressed to the device's current short address, and its endpoint and cluster must be written as `0x` plus 1-4 hex digits (or a single digit); anything else is refused. A `he cmd` command id must fit in one byte (`0x00`-`0xFF`). The ZDO requests that change the whole network, `0x0036` (permit joining) and `0x0038` (network update), are refused: the hub runs the network. A raw command waits until no request to the device is pending. Its reply is not matched; it reaches the device's driver and the Zigbee logs.
  - Binding: Bind or Unbind a cluster of the device to the hub (the hub's own IEEE address, endpoint `01` by default) or to a group.
  - Leave and firmware: **Leave the network** (optionally asking the device to rejoin) and **Firmware update notify** (an OTA Image Notify, after which the device asks the hub for an update).
  - Confirmation: every button that changes a device (write, configure reporting, cluster command, raw command, bind, unbind, leave, firmware notify) only checks the form, builds the frame for the device's current address and shows a **Confirm** link. The confirmation page shows the device, its IEEE and short address, what will happen and the exact frame, with **Confirm** and **Cancel** (no Done). It warns when a frame does more than its form suggests: Reset to Factory Defaults (Basic cluster command `00`), a Basic cluster frame it cannot read, the leave cluster `0x0034` or the OTA cluster `0x0019`. A confirmation is valid once, for 2 minutes, and preparing another one cancels it. Nothing is sent if the device's short address changed in the meantime.
- **Hub page:** the coordinator's own tables from the hub (neighbours with LQI and costs, children, routes), the lowest neighbour LQI, the channel, PAN ID and power level.

Requests go out one at a time per device and at most four at once in all; each waits for its reply, with no fixed delay between them. A request with no reply after 10 seconds is reported as a timeout; for a device whose node descriptor says it turns its receiver off when idle (or before the descriptor has been read), the message adds that it answers only when awake. A device can also ignore a cluster it lists: one plug never answered on Identify, so a report reuses an interview finished in the last 10 minutes even when it had failed steps, and lists them under Failures. A device that answers with an error status (many routers answer `NOT_SUPPORTED` to a routing table request) is reported with that status. A failed result keeps the request frame that was sent and the raw reply, if one came.

## Monitor devices

A monitor device (driver Zigbee Workbench Monitor, network ID `zbworkbench-mon-<IEEE>`) is an ordinary device, not a component, so dashboards, rules and other apps can use it. It publishes only what the device page also shows:

| Attribute | Value |
|---|---|
| `healthStatus` | `online`, `offline` (also when the hub no longer lists the device) or `unknown` (no message time, or the device is no longer picked) |
| `neighborCount` | entries in the device's neighbour table |
| `childCount` | neighbours whose relationship is Child |
| `routeCount` | active entries in its routing table (not sent when the device refuses the routing table) |
| `parent` | name of its Parent neighbour, `none` without one |
| `lowestLqi` | the weakest neighbour, as `<lqi> <name>` |
| `lastPoll` | time of the last poll attempt, ISO 8601; the table values follow when the replies arrive |

Every poll interval, at a random offset, the app reads the hub's Zigbee details for health, and for routers also the neighbour and routing tables; end devices get health only. A router that answered the routing table with `NOT_SUPPORTED` is not asked for it again, and a poll skips a table already being read for that device, so it never restarts a read you started. The monitor device's **Refresh** asks the app for a poll. Values are sent only when they change. A monitor whose device is dropped from the picker is kept, set to `unknown` and listed on the main page until you remove it, since dashboards and rules may use it.

## How it works

- **The app** builds the request frames, finds each device's current short address by its IEEE address in the hub's Zigbee details, matches replies to requests, decodes them and keeps the results in its state.
- **The radio device** sends the frames and reads the hub's Zigbee log socket, passing on only frames from the addresses the app is waiting on. It keeps the socket open all the time: it connects when it starts (also after a hub restart) and reconnects after any drop: 10 seconds after the first, doubling with each failed attempt in a row up to 5 minutes, and back to 10 seconds once the socket delivers a message. A request made while the socket is down is reported as lost ("Lost the Zigbee log connection"), and so are the requests waiting for replies when it drops.

The hub writes its own sequence number into every frame sent this way, so a reply cannot be matched by sequence number. The app matches a reply by the device's address, the reply cluster and its content (the table start index, the endpoint, the attributes asked for, the command a default response answers), and sends one request at a time per device and reply cluster. A cluster command is answered by its default response or by a command from the device in the other direction; the attribute report a command triggers is not taken for its reply. A firmware notify is answered by the device's Query Next Image Request, which the hub's own firmware server then answers. A reply the device's own driver triggered on the same cluster at the same moment could still be taken for the app's.

## Tests

- `tests/test-zigbee-codec.groovy`, `tests/test-request-engine.groovy`, `tests/test-radio-filter.groovy`, `tests/test-report.groovy`: unit tests of the frame builders and decoders, the request engine (reply matching, deadlines, table paging, address lookup), the radio device's frame filter and the report (its fields, masking, size limit, file names and retention), run off the hub: `groovy tests/test-request-engine.groovy`.
- `tests/test-alias-parity.sh`: runs Hub Inspector's alias function under `node` and the Workbench's under `groovy` on one list of 3,348 names (collisions, accents, emoji, more names than alias pairs) and checks every alias matches.
- `tests/test-zigbee-workbench.sh`: live test against a real Zigbee router. It installs what is missing, picks the device in the app, runs each request, checks the decoded results against the device's own data, and checks that the device's driver, network ID, state, settings and data did not change. Besides reads, it writes back the StartUpOnOff value (`0006/4003`) it has just read, through the confirmation page (which must show the write's frame), and reads the reporting configuration of OnOff. It presses Leave without confirming and checks that nothing is sent. It turns monitoring on, polls, checks the monitor device's values against the app's neighbour table, drops the device from the picker and checks the monitor stays (listed as orphaned), picks it again, then removes the monitor through its confirmation page and checks that it is gone. With `RUN_SLOW_TESTS=1` it also runs an interview and a report, checks the saved file is masked and named by model, and checks that only the newest 3 reports for the model remain. It never confirms a leave or a firmware notify: `bash tests/test-zigbee-workbench.sh [@hub] --device "My Zigbee Plug"` (the label of one of your Zigbee routers; `RUN_SLOW_TESTS=1` adds the longer cases).
