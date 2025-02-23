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
▸ Manufacturer Code                         = 0x1185 = STELPRO
▸ Maximum Buffer Size                       = 89 bytes
▸ Maximum Incoming Transfer Size            = 61 bytes
▸ Primary Trust Center                      = No
▸ Backup Trust Center                       = No
▸ Primary Binding Table Cache               = No
▸ Backup Binding Table Cache                = No
▸ Primary Discovery Cache                   = No
▸ Backup Discovery Cache                    = No
▸ Network Manager                           = No
▸ Maximum Outgoing Transfer Size            = 61 bytes
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
Endpoint 0x19
================================================================================================
Out Cluster: 0x0402 (Temperature Measurement Cluster)
------------------------------------------------------------------------------------------------
▸ No generated commands
================================================================================================
In Cluster: 0x0000 (Basic Cluster)
------------------------------------------------------------------------------------------------
▸ 0_0000 | ZCL Version          | req | r-- | uint8  | 01 = 1                          | --
▸ 0_0001 | Application Version  | opt | r-- | uint8  | 6A = 106                        | --
▸ 0_0002 | Stack Version        | opt | r-- | uint8  | 04 = 4                          | --
▸ 0_0003 | HW Version           | opt | r-- | uint8  | 01 = 1                          | --
▸ 0_0004 | Manufacturer Name    | opt | r-- | string | Stelpro | --
▸ 0_0005 | Model Identifier     | opt | r-- | string | ST218¶c¹  | --
▸ 0_0006 | Date Code            | req | r-- | string | 20180306 00113                 | --
▸ 0_0007 | Power Source         | opt | r-- | enum8  | 01 = Mains (single phase)       | --
▸ 0_0010 | Location Description | opt | rw- | string | ThermostatØ                 | --
▸ 0_0011 | Physical Environment | opt | rw- | enum8  | 00                              | --
------------------------------------------------------------------------------------------------
▸ 0_00 | Reset to Factory Defaults | opt
================================================================================================
In Cluster: 0x0003 (Identify Cluster)
------------------------------------------------------------------------------------------------
▸ 0x0000 | Identify Time | req | rw- | uint16 | 0000 = 0 seconds | --
▸ 0x0001 | --            | --  | r-- | map8   | 00 = 00000000    | --
------------------------------------------------------------------------------------------------
▸ 0_00 | Identify       | req
▸ 0_01 | Identify Query | req
▸ 0_02 | --             | --
▸ 0_03 | --             | --
================================================================================================
In Cluster: 0x0004 (Groups Cluster)
------------------------------------------------------------------------------------------------
▸ 0_0000 | Name Support | req | r-- | map8 | 00 = 00000000 | --
------------------------------------------------------------------------------------------------
▸ 0_00 | Add Group                | req
▸ 0_01 | View Group               | req
▸ 0_02 | Get Group Membership     | req
▸ 0_03 | Remove Group             | req
▸ 0_04 | Remove All Groups        | req
▸ 0_05 | Add Group If Identifying | req
================================================================================================
In Cluster: 0x0201 (Thermostat Cluster)
------------------------------------------------------------------------------------------------
▸ No attributes
------------------------------------------------------------------------------------------------
▸ 0_00 | Setpoint Raise/Lower | req
================================================================================================
In Cluster: 0x0204 (Thermostat User Interface Configuration Cluster)
------------------------------------------------------------------------------------------------
▸ 0_0000 | Temperature Display Mode        | req | r-- | enum8 | 00 | --
▸ 0_0001 | Keypad Lockout                  | req | rw- | enum8 | 00 | --
▸ 0_0002 | Schedule Programming Visibility | opt | rw- | enum8 | 00 | --
------------------------------------------------------------------------------------------------
▸ 0_00 | -- | --
================================================================================================
Bindings Table
------------------------------------------------------------------------------------------------
▸ Src:F8F005FFFFD0B6D2 | Endpoint:0x01 | Cluster:0x0201 | Dest:000D6F0017400082 | Endpoint:0x19
▸ Src:F8F005FFFFD0B6D2 | Endpoint:0x19 | Cluster:0x0201 | Dest:000D6F0017400082 | Endpoint:0x01
▸ Src:F8F005FFFFD0B6D2 | Endpoint:0x19 | Cluster:0x0204 | Dest:000D6F0017400082 | Endpoint:0x01
================================================================================================
Neighbors Table
------------------------------------------------------------------------------------------------
▸ Addr:E643 | Type:Zigbee Router      | RxOnWhenIdle:Yes | Rel:Unknown | Permit Joining:Yes | Depth:2
▸ Addr:0B3B | Type:Zigbee Router      | RxOnWhenIdle:Yes | Rel:Unknown | Permit Joining:Yes | Depth:2
▸ Addr:1194 | Type:Zigbee Router      | RxOnWhenIdle:Yes | Rel:Unknown | Permit Joining:Yes | Depth:2
▸ Addr:B644 | Type:Zigbee Router      | RxOnWhenIdle:Yes | Rel:Unknown | Permit Joining:Yes | Depth:2
▸ Addr:0000 | Type:Zigbee Coordinator | RxOnWhenIdle:Yes | Rel:Unknown | Permit Joining:Yes | Depth:0
================================================================================================
Routing Table
------------------------------------------------------------------------------------------------
▸ Destination:0000 | Next Hop:0000 | Route Status:Active
▸ Destination:889F | Next Hop:889F | Route Status:Active
▸ Destination:3EB1 | Next Hop:F417 | Route Status:Active
▸ Destination:E643 | Next Hop:B644 | Route Status:Active
▸ Destination:EF32 | Next Hop:F417 | Route Status:Active
 */
