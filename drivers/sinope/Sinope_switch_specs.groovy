/*

================================================================================================
Node Descriptor
------------------------------------------------------------------------------------------------
▸ Logical Type                              = Zigbee Router
▸ Complex Descriptor Available              = No
▸ User Descriptor Available                 = Yes
▸ Fragmentation Supported (R23)             = No
▸ Frequency Band                            = Reserved
▸ Alternate PAN Coordinator                 = No
▸ Device Type                               = Full Function Device (FFD)
▸ Mains Power Source                        = Yes
▸ Receiver On When Idle                     = Yes (always on)
▸ Security Capability                       = No
▸ Allocate Address                          = Yes
▸ Manufacturer Code                         = 0x119C = SINOPE
▸ Maximum Buffer Size                       = 71 bytes
▸ Maximum Incoming Transfer Size            = 43 bytes
▸ Primary Trust Center                      = No
▸ Backup Trust Center                       = No
▸ Primary Binding Table Cache               = Yes
▸ Backup Binding Table Cache                = No
▸ Primary Discovery Cache                   = Yes
▸ Backup Discovery Cache                    = No
▸ Network Manager                           = No
▸ Maximum Outgoing Transfer Size            = 43 bytes
▸ Extended Active Endpoint List Available   = No
▸ Extended Simple Descriptor List Available = No
================================================================================================
Power Descriptor
------------------------------------------------------------------------------------------------
▸ Current Power Mode         = Same as "Receiver On When Idle" from "Node Descriptor" section above
▸ Available Power Sources    = [Constant (mains) power, Rechargeable battery, Disposable battery]
▸ Current Power Sources      = [Constant (mains) power]
▸ Current Power Source Level = 100%
================================================================================================
Endpoint 0x01
================================================================================================
Out Cluster: 0x0003 (Identify Cluster)
------------------------------------------------------------------------------------------------
▸ 0_00 | Identify | req
================================================================================================
Out Cluster: 0x0004 (Groups Cluster)
------------------------------------------------------------------------------------------------
▸ 0_00 | Add Group            | req
▸ 0_01 | View Group           | req
▸ 0_02 | Get Group Membership | req
▸ 0_03 | Remove Group         | req
================================================================================================
Out Cluster: 0x0019 (OTA Upgrade Cluster)
------------------------------------------------------------------------------------------------
▸ No generated commands
================================================================================================
In Cluster: 0x0000 (Basic Cluster)
------------------------------------------------------------------------------------------------
▸ 0_0000 | ZCL Version          | req | r-- | uint8  | 02 = 2                          | --
▸ 0_0001 | Application Version  | opt | r-- | uint8  | 84 = 132                        | --
▸ 0_0002 | Stack Version        | opt | r-- | uint8  | 01 = 1                          | --
▸ 0_0003 | HW Version           | opt | r-- | uint8  | 00 = 0                          | --
▸ 0_0004 | Manufacturer Name    | opt | r-- | string | Sinope Technologies | --
▸ 0_0005 | Model Identifier     | opt | r-- | string | DM2500ZB | --
▸ 0_0007 | Power Source         | opt | r-- | enum8  | 01 = Mains (single phase)       | --
▸ 0_0010 | Location Description | opt | rw- | string |                  | --
▸ 0_0011 | Physical Environment | opt | rw- | enum8  | 00                              | --
▸ 0_4000 | SW Build ID          | opt | r-- | string | 673                 | --
▸ 0_FFFD | Cluster Revision     | req | r-- | uint16 | 0001 = 1                        | --
------------------------------------------------------------------------------------------------
▸ 0_00 | Reset to Factory Defaults | opt
================================================================================================
In Cluster: 0x0002 (Temperature Configuration Cluster)
------------------------------------------------------------------------------------------------
▸ 0_0000 | Current Temperature | req | r-- | int16  | 001B = 27 | --
▸ 0_FFFD | Cluster Revision    | req | r-- | uint16 | 0001 = 1  | --
------------------------------------------------------------------------------------------------
▸ No received commands
================================================================================================
In Cluster: 0x0003 (Identify Cluster)
------------------------------------------------------------------------------------------------
▸ 0_0000 | Identify Time    | req | rw- | uint16 | 0000 = 0 seconds | --
▸ 0_FFFD | Cluster Revision | req | r-- | uint16 | 0001 = 1         | --
------------------------------------------------------------------------------------------------
▸ 0_00 | Identify       | req
▸ 0_01 | Identify Query | req
================================================================================================
In Cluster: 0x0004 (Groups Cluster)
------------------------------------------------------------------------------------------------
▸ 0_0000 | Name Support     | req | r-- | map8   | 00 = 00000000 | --
▸ 0_FFFD | Cluster Revision | req | r-- | uint16 | 0001 = 1      | --
------------------------------------------------------------------------------------------------
▸ 0_00 | Add Group                | req
▸ 0_01 | View Group               | req
▸ 0_02 | Get Group Membership     | req
▸ 0_03 | Remove Group             | req
▸ 0_04 | Remove All Groups        | req
▸ 0_05 | Add Group If Identifying | req
================================================================================================
In Cluster: 0x0005 (Scenes Cluster)
------------------------------------------------------------------------------------------------
▸ 0_0000 | Scene Count      | req | r-- | uint8  | 00 = 0        | --
▸ 0_0001 | Current Scene    | req | r-- | uint8  | 00 = 0        | --
▸ 0_0002 | Current Group    | req | r-- | uint16 | 0000 = 0      | --
▸ 0_0003 | Scene Valid      | req | r-- | bool   | 00 = False    | --
▸ 0_0004 | Name Support     | req | r-- | map8   | 00 = 00000000 | --
▸ 0_FFFD | Cluster Revision | req | r-- | uint16 | 0001 = 1      | --
------------------------------------------------------------------------------------------------
▸ 0_00 | Add Scene            | req
▸ 0_01 | View Scene           | req
▸ 0_02 | Remove Scene         | req
▸ 0_03 | Remove All Scenes    | req
▸ 0_04 | Store Scene          | req
▸ 0_05 | Recall Scene         | req
▸ 0_06 | Get Scene Membership | req
▸ 0_40 | Enhanced Add Scene   | opt
▸ 0_41 | Enhanced View Scene  | opt
▸ 0_42 | Copy Scene           | opt
================================================================================================
In Cluster: 0x0006 (On/Off Cluster)
------------------------------------------------------------------------------------------------
▸ 0_0000 | On Off           | req | r-p | bool   | 00 = Off         | 0..65534
▸ 0_4001 | On Time          | opt | rw- | uint16 | 0000 = 0 seconds | --
▸ 0_4002 | Off Wait Time    | opt | rw- | uint16 | 0000 = 0 seconds | --
▸ 0_FFFD | Cluster Revision | req | r-- | uint16 | 0001 = 1         | --
------------------------------------------------------------------------------------------------
▸ 0_00 | Off               | req
▸ 0_01 | On                | req
▸ 0_02 | Toggle            | req
▸ 0_42 | On With Timed Off | opt
================================================================================================
In Cluster: 0x0008 (Level Control Cluster)
------------------------------------------------------------------------------------------------
▸ 0_0000 | Current Level    | req | r-p | uint8  | 4C = 29%        | 5..65534
▸ 0_000F | Options          | req | rw- | map8   | 00 = 00000000   | --
▸ 0_0011 | On Level         | opt | rw- | uint8  | FF = Last level | --
▸ 0_FFFD | Cluster Revision | req | r-- | uint16 | 0001 = 1        | --
------------------------------------------------------------------------------------------------
▸ 0_00 | Move To Level             | req
▸ 0_01 | Move                      | req
▸ 0_02 | Step Level                | req
▸ 0_03 | Stop                      | req
▸ 0_04 | Move To Level With On/Off | req
▸ 0_05 | Move With On/Off          | req
▸ 0_06 | Step With On/Off          | req
▸ 0_07 | Stop                      | req
================================================================================================
In Cluster: 0x0702 (Metering (Smart Energy) Cluster)
------------------------------------------------------------------------------------------------
▸ 0_0000 | Current Summation Delivered | req | r-- | uint48 | 000000000000 = 0       | --
▸ 0_0200 | --                          | --  | r-- | map8   | 00 = 00000000          | --
▸ 0_0300 | Unit of Measure             | req | r-- | enum8  | 00 = kWh & kW          | --
▸ 0_0301 | Multiplier                  | opt | r-- | uint24 | 000001 = 1             | --
▸ 0_0302 | Divisor                     | opt | r-- | uint24 | 0003E8 = 1000          | --
▸ 0_0303 | Summation Formatting        | req | r-- | map8   | C1 = 11000001          | --
▸ 0_0306 | Metering Device Type        | req | r-- | map8   | 00 = Electric Metering | --
▸ 0_FFFD | Cluster Revision            | req | r-- | uint16 | 0001 = 1               | --
------------------------------------------------------------------------------------------------
▸ No received commands
================================================================================================
In Cluster: 0x0B05 (Diagnostics Cluster)
------------------------------------------------------------------------------------------------
▸ 0_0000 | Number Of Resets                       | opt | r-- | uint16 | 0005 = 5  | --
▸ 0_0001 | Persistent Memory Writes               | opt | r-- | uint16 | 002D = 45 | --
▸ 0_011B | Average MAC Retry Per APS Message Sent | opt | r-- | uint16 | 0000 = 0  | --
▸ 0_011C | Last Message LQI                       | opt | r-- | uint8  | BF = 191  | --
▸ 0_011D | Last Message RSSI                      | opt | r-- | int8   | BA = -58  | --
▸ 0_FFFD | Cluster Revision                       | req | r-- | uint16 | 0001 = 1  | --
------------------------------------------------------------------------------------------------
▸ No received commands
================================================================================================
In Cluster: 0xFF01 (Unknown Cluster)
------------------------------------------------------------------------------------------------
▸ No attributes
------------------------------------------------------------------------------------------------
▸ 0_01 | -- | --
▸ 0_0F | -- | --
================================================================================================
Bindings Table
------------------------------------------------------------------------------------------------
▸ Src:500B91400006499B | Endpoint:0x01 | Cluster:0x0008 | Dest:000D6F00180DD83D | Endpoint:0x01
▸ Src:500B91400006499B | Endpoint:0x01 | Cluster:0x0006 | Dest:000D6F00180DD83D | Endpoint:0x01


 See also: https://raw.githubusercontent.com/claudegel/sinope-zha/refs/heads/master/README.md

 - lights and dimmer: (SW2500ZB, DM2500ZB, DM2550ZB)

|Cluster|Attributes|Atribute decimal|Data type|Fonction |Value|Access|
| --- | --- | --- | --- | --- | --- | ---|
|0xff01|0x0001|1|t.bool|Unknown|0, 1|read/write|
|0xff01|0x0002|2|t.enum8|KeypadLock| Locked: 1, Unlocked: 0|read/write|
|0xff01|0x0003|3|t.uint16_t|firmware_number| |read|
|0xff01|0x0004|4|t.CharacterString|firmware_version| |read|
|0xff01|0x0010|16|t.int16s|on_intensity|minIntensity - 3000|read/write|
|0xff01|0x0012|18|t.enum8|Unknown|0, 1|read/write|
|0xff01|0x0013|19|t.enum8|unknown|0, 1, 2|read/write|
|0xff01|0x0050|80|t.uint24_t|onLedColor| 0x0affdc - Lim, 0x000a4b - Amber, 0x0100a5 - Fushia, 0x64ffff - Perle, 0xffff00 - Blue|read/write|
|0xff01|0x0051|81|t.uint24_t|offLedColor| 0x0affdc - Lim, 0x000a4b - Amber, 0x0100a5 - Fushia, 0x64ffff - Perle, 0xffff00 - Blue|read/write|
|0xff01|0x0052|82|t.uint8_t|onLedIntensity| Percent|read/write|
|0xff01|0x0053|83|t.uint8_t|offLedIntensity| Percent|read/write|
|0xff01|0x0054|84|t.enum8|actionReport| singleTapUp: 2, doubleTapUp: 4, singleTapDown: 18, doubleTapDown: 20|read/repport|
|0xff01|0x0055|85|t.uint16_t|minIntensity| 0 to 3000|read/write|
|0xff01|0x0056|86|t.enum8|phase_control|0=forward, 1=reverse|read/write|
|0xff01|0x0058|88|t.enum8|double_up_full|0=off, 1=on|read/write|
|0xff01|0x0080|128|t.uint32_t|Unknown|16908288|read|
|0xff01|0x0090|144|t.uint32_t|currentSummationDelivered|watt/hr|read/report|
|0xff01|0x00A0|160|t.uint32_t|Timer|Time, 1 to 10800 seconds|read/write|
|0xff01|0x00A1|161|t.uint32_t|Timer_countdown|Seconds remaining on timer|read|
|0xff01|0x0119|281|t.uint16_t|ConnectedLoad| None: 0, watt|read/write|
|0xff01|0x0200|512|t.bitmap32|status| 0x00000000| report/read|
|0xff01|0xFFFD|65533|t.uint16_t|cluster_revision|1|read|
| --- | --- | --- | --- | --- | --- | ---|
|0x0702|0x0000|0|t.uint48_t|CurrentSummationDelivered| Sum of delivered watt/hr|read/report|
| --- | --- | --- | --- | --- | --- | ---|
|0x0006|0x0000|0|t.Bool|OnOff| 1=on, 0=off|report/read/write|
| --- | --- | --- | --- | --- | --- | ---|
|0x0008|0x0000|0|t.uint8_t|CurrentLevel| 0=0%, 254=100%|read/report|
|0x0008|0x0011|17|t.uint8_t|OnLevel| 0=0%, 254=100%|read/write|


 */
