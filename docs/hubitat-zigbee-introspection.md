# Hubitat `zigbee` helper — reflective introspection

Generated: `Thu Jun 04 23:17:58 EDT 2026`  
Hub firmware: `2.5.0.148`  
Source: driver `Zigbee Introspect` (iamtrep namespace), Groovy reflection on the `zigbee` object injected into drivers.

## `zigbee.properties` (bean-property snapshot)

Per-instance readable getters via Groovy meta-bean accessor. For just-the-name use of any object: `getObjectClassName(obj)`.

- `class` = `class com.hubitat.zigbee.Zigbee`
- `cluster` = `class com.hubitat.zigbee.Zigbee$ZigbeeCluster`
- `device` = `Zigbee Introspect`
- `deviceNetworkId` = `ZIGBEE_INTROSPECT`
- `endpointId` = `null`
- `zigbeeId` = `null`

_(6 bean properties)_

## `zigbee` (`com.hubitat.zigbee.Zigbee`)

- Class: `com.hubitat.zigbee.Zigbee`
- Superclass: `java.lang.Object`
- Interfaces: `groovy.lang.GroovyObject`

### Methods

- `void __$swapInit()`
- `List batteryConfig()`
- `ZigbeeCluster clusterLookup(Object)`
- `List colorTemperatureConfig()`
- `List colorTemperatureRefresh()`
- `List colorTemperatureRefresh(int)`
- `List command(Integer, Integer, String[])`
- `List command(Integer, Integer, Map, String[])`
- `List command(Integer, Integer, Map, int, String[])`
- `List configureReporting(Integer, Integer, Integer, Integer, Integer)`
- `List configureReporting(Integer, Integer, Integer, Integer, Integer, Integer)`
- `List configureReporting(Integer, Integer, Integer, Integer, Integer, Integer, Map)`
- `List configureReporting(Integer, Integer, Integer, Integer, Integer, Integer, Map, int)`
- `Integer convertHexToInt(String)`
- `String convertToHexString(Integer, Integer)`
- `List electricMeasurementPowerRefresh()`
- `List electricMeasurementPowerRefresh(int)`
- `List enrollResponse()`
- `List enrollResponse(int)`
- `List enrollResponse(int, Map)`
- `Class getCluster()`
- `DeviceWrapper getDevice()`
- `Map getEvent(String)`
- `Map getKnownDescription(String)`
- `MetaClass getMetaClass()`
- `Object getProperty(String)`
- `List groupOff(Integer)`
- `List groupOff(Integer, int)`
- `List groupOn(Integer)`
- `List groupOn(Integer, int)`
- `String hex2String(String)`
- `Object invokeMethod(String, Object)`
- `List levelConfig()`
- `List levelRefresh()`
- `List levelRefresh(int)`
- `List off()`
- `List off(int)`
- `List on()`
- `List on(int)`
- `List onOffConfig()`
- `List onOffConfig(String)`
- `List onOffConfig(Integer, Integer)`
- `List onOffConfig(String, Integer, Integer)`
- `List onOffRefresh()`
- `List onOffRefresh(int)`
- `SmartShield parse(String)`
- `Map parseDescriptionAsMap(String)`
- `ZoneStatus parseZoneStatus(String)`
- `Map parseZoneStatusChange(String)`
- `List readAttribute(Integer, List)`
- `List readAttribute(Integer, Integer)`
- `List readAttribute(Integer, Integer, Map)`
- `List readAttribute(Integer, List, Map)`
- `List readAttribute(Integer, Integer, Map, int)`
- `List readAttribute(Integer, List, Map, int)`
- `List refreshData(String, String)`
- `List reportingConfiguration(Integer, Integer)`
- `List reportingConfiguration(Integer, Integer, Map)`
- `List reportingConfiguration(Integer, Integer, Map, int)`
- `List setColor(Map)`
- `List setColor(Map, int)`
- `List setColorTemperature(String)`
- `List setColorTemperature(BigDecimal)`
- `List setColorTemperature(Integer)`
- `List setColorTemperature(Integer, int)`
- `List setColorTemperature(String, int)`
- `List setColorTemperature(BigDecimal, int)`
- `List setColorTemperature(Integer, int, int)`
- `List setColorTemperature(String, int, int)`
- `List setColorTemperature(BigDecimal, int, int)`
- `List setColorXY(Map)`
- `List setColorXY(Map, int)`
- `void setDevice(DeviceWrapper)`
- `List setGroupColorTemperature(Integer, Integer)`
- `List setGroupColorTemperature(Integer, String)`
- `List setGroupColorTemperature(Integer, BigDecimal)`
- `List setGroupColorTemperature(Integer, Integer, int)`
- `List setGroupColorTemperature(Integer, BigDecimal, int)`
- `List setGroupColorTemperature(Integer, String, int)`
- `List setGroupColorTemperature(Integer, Integer, int, int)`
- `List setGroupColorTemperature(Integer, BigDecimal, int, int)`
- `List setGroupColorTemperature(Integer, String, int, int)`
- `List setGroupLevel(Integer, BigDecimal)`
- `List setGroupLevel(Integer, Integer)`
- `List setGroupLevel(Integer, Integer, BigDecimal)`
- `List setGroupLevel(Integer, BigDecimal, BigDecimal)`
- `List setGroupLevel(Integer, Integer, BigDecimal, int)`
- `List setHue(Integer)`
- `List setHue(Integer, int)`
- `List setHue(Integer, int, int)`
- `List setLevel(BigDecimal)`
- `List setLevel(Integer)`
- `List setLevel(Integer, Integer)`
- `List setLevel(Integer, BigDecimal)`
- `List setLevel(BigDecimal, BigDecimal)`
- `List setLevel(Integer, BigDecimal, int)`
- `void setMetaClass(MetaClass)`
- `void setProperty(String, Object)`
- `List setSaturation(Integer)`
- `List setSaturation(Integer, int)`
- `List setSaturation(Integer, int, int)`
- `String swapOctets(String)`
- `List temperatureConfig()`
- `List temperatureConfig(Integer, Integer)`
- `Object this$dist$get$1(String)`
- `Object this$dist$invoke$1(String, Object)`
- `void this$dist$set$1(String, Object)`
- `List updateFirmware()`
- `List updateFirmware(Map)`
- `List writeAttribute(Integer, Integer, Integer, String)`
- `List writeAttribute(Integer, Integer, Integer, Integer)`
- `List writeAttribute(Integer, Integer, Integer, Integer, Map)`
- `List writeAttribute(Integer, Integer, Integer, String, Map)`
- `List writeAttribute(Integer, Integer, Integer, String, Map, int)`
- `List writeAttribute(Integer, Integer, Integer, Integer, Map, int)`

### Fields

- `int ALARMS_CLUSTER` = `9`
- `int ANALOG_INPUT_CLUSTER` = `12`
- `int ANALOG_OUTPUT_CLUSTER` = `13`
- `int ANALOG_VALUE_CLUSTER` = `14`
- `int BALLAST_CONFIGURATION_CLUSTER` = `769`
- `int BASIC_CLUSTER` = `0`
- `int BINARY_INPUT_CLUSTER` = `15`
- `int BINARY_OUTPUT_CLUSTER` = `16`
- `int BINARY_VALUE_CLUSTER` = `17`
- `int COLOR_CONTROL_CLUSTER` = `768`
- `int DEHUMIDIFICATION_CONTROL_CLUSTER` = `515`
- `int DEMAND_RESPONSE_CLUSTER` = `1793`
- `int DIAGNOSTICS_CLUSTER` = `2821`
- `int DOOR_LOCK_CLUSTER` = `257`
- `int ELECTRICAL_MEASUREMENT_CLUSTER` = `2820`
- `int FAN_CONTROL_CLUSTER` = `514`
- `int FLOW_MEASUREMENT_CLUSTER` = `1028`
- `int GROUPS_CLUSTER` = `4`
- `int IAS_ACE_CLUSTER` = `1281`
- `int IAS_WD_CLUSTER` = `1282`
- `int IAS_ZONE_CLUSTER` = `1280`
- `int IDENTIFY_CLUSTER` = `3`
- `int ILLUMINANCE_LEVEL_SENSING_CLUSTER` = `1025`
- `int ILLUMINANCE_MEASUREMENT_CLUSTER` = `1024`
- `int KEY_ESTABLISHMENT_CLUSTER` = `2048`
- `int LEVEL_CONTROL_CLUSTER` = `8`
- `int MESSAGING_CLUSTER` = `1795`
- `int METERING_CLUSTER` = `1794`
- `int METER_IDENTIFICATION_CLUSTER` = `2817`
- `int MULTISTATE_INPUT_CLUSTER` = `18`
- `int MULTISTATE_OUTPUT_CLUSTER` = `19`
- `int MULTISTATE_VALUE_CLUSTER` = `20`
- `int OCCUPANCY_SENSING_CLUSTER` = `1030`
- `int ON_OFF_CLUSTER` = `6`
- `int ON_OFF_SWITCH_CONFIGURATION_CLUSTER` = `7`
- `int OTA_CLUSTER` = `25`
- `int POLL_CONTROL_CLUSTER` = `32`
- `int POWER_CONFIGURATION_CLUSTER` = `1`
- `int POWER_PROFILE_CLUSTER` = `26`
- `int PRESSURE_MEASUREMENT_CLUSTER` = `1027`
- `int PRICE_CLUSTER` = `1792`
- `int PUMP_CONFIGURATION_CONTROL_CLUSTER` = `512`
- `int RELATIVE_HUMIDITY_MEASUREMENT_CLUSTER` = `1029`
- `int RSSI_LOCATION_CLUSTER` = `11`
- `int SCENES_CLUSTER` = `5`
- `int SHADE_CONFIGURATION_CLUSTER` = `256`
- `int SOIL_MOISTURE_MEASUREMENT` = `1032`
- `int TEMPERATURE_CONFIGURATION_CLUSTER` = `2`
- `int TEMPERATURE_MEASUREMENT_CLUSTER` = `1026`
- `int THERMOSTAT_CLUSTER` = `513`
- `int THERMOSTAT_USER_INTERFACE_CONFIGURATION_CLUSTER` = `516`
- `int TIME_CLUSTER` = `10`
- `int TUNNELING_CLUSTER` = `1796`
- `int WINDOW_COVERING_CLUSTER` = `258`
- `boolean __$stMC` = `false`
- `String copyright` = `Copyright 2016-2023 Hubitat Inc.  All Rights Reserved.`

## com.hubitat.zigbee.Zigbee$ZigbeeCluster

- Class: `com.hubitat.zigbee.Zigbee$ZigbeeCluster`
- Superclass: `java.lang.Enum`
- Interfaces: `groovy.lang.GroovyObject`

### Methods

- `ZigbeeCluster $INIT(Object[])`
- `int compareTo(Object)`
- `int compareTo(Enum)`
- `boolean equals(Object)`
- `ZigbeeCluster getCluster(Integer)`
- `ZigbeeCluster getCluster(String)`
- `String getClusterEnum()`
- `Integer getClusterInt()`
- `String getClusterLabel()`
- `Class getDeclaringClass()`
- `MetaClass getMetaClass()`
- `Object getProperty(String)`
- `int hashCode()`
- `Object invokeMethod(String, Object)`
- `String name()`
- `ZigbeeCluster next()`
- `int ordinal()`
- `ZigbeeCluster previous()`
- `void setMetaClass(MetaClass)`
- `void setProperty(String, Object)`
- `String toString()`
- `ZigbeeCluster valueOf(String)`
- `Enum valueOf(Class, String)`
- `ZigbeeCluster[] values()`

### Fields

- `ZigbeeCluster ALARMS_CLUSTER` = `ALARMS_CLUSTER`
- `ZigbeeCluster ANALOG_INPUT_CLUSTER` = `ANALOG_INPUT_CLUSTER`
- `ZigbeeCluster ANALOG_OUTPUT_CLUSTER` = `ANALOG_OUTPUT_CLUSTER`
- `ZigbeeCluster ANALOG_VALUE_CLUSTER` = `ANALOG_VALUE_CLUSTER`
- `ZigbeeCluster BALLAST_CONFIGURATION_CLUSTER` = `BALLAST_CONFIGURATION_CLUSTER`
- `ZigbeeCluster BASIC_CLUSTER` = `BASIC_CLUSTER`
- `ZigbeeCluster BINARY_INPUT_CLUSTER` = `BINARY_INPUT_CLUSTER`
- `ZigbeeCluster BINARY_OUTPUT_CLUSTER` = `BINARY_OUTPUT_CLUSTER`
- `ZigbeeCluster BINARY_VALUE_CLUSTER` = `BINARY_VALUE_CLUSTER`
- `ZigbeeCluster COLOR_CONTROL_CLUSTER` = `COLOR_CONTROL_CLUSTER`
- `ZigbeeCluster DEHUMIDIFICATION_CONTROL_CLUSTER` = `DEHUMIDIFICATION_CONTROL_CLUSTER`
- `ZigbeeCluster DEMAND_RESPONSE_CLUSTER` = `DEMAND_RESPONSE_CLUSTER`
- `ZigbeeCluster DIAGNOSTICS_CLUSTER` = `DIAGNOSTICS_CLUSTER`
- `ZigbeeCluster DOOR_LOCK_CLUSTER` = `DOOR_LOCK_CLUSTER`
- `ZigbeeCluster ELECTRICAL_MEASUREMENT_CLUSTER` = `ELECTRICAL_MEASUREMENT_CLUSTER`
- `ZigbeeCluster FAN_CONTROL_CLUSTER` = `FAN_CONTROL_CLUSTER`
- `ZigbeeCluster FLOW_MEASUREMENT_CLUSTER` = `FLOW_MEASUREMENT_CLUSTER`
- `ZigbeeCluster GROUPS_CLUSTER` = `GROUPS_CLUSTER`
- `ZigbeeCluster IAS_ACE_CLUSTER` = `IAS_ACE_CLUSTER`
- `ZigbeeCluster IAS_WD_CLUSTER` = `IAS_WD_CLUSTER`
- `ZigbeeCluster IAS_ZONE_CLUSTER` = `IAS_ZONE_CLUSTER`
- `ZigbeeCluster IDENTIFY_CLUSTER` = `IDENTIFY_CLUSTER`
- `ZigbeeCluster ILLUMINANCE_LEVEL_SENSING_CLUSTER` = `ILLUMINANCE_LEVEL_SENSING_CLUSTER`
- `ZigbeeCluster ILLUMINANCE_MEASUREMENT_CLUSTER` = `ILLUMINANCE_MEASUREMENT_CLUSTER`
- `ZigbeeCluster KEY_ESTABLISHMENT_CLUSTER` = `KEY_ESTABLISHMENT_CLUSTER`
- `ZigbeeCluster LEVEL_CONTROL_CLUSTER` = `LEVEL_CONTROL_CLUSTER`
- `ZigbeeCluster MAX_VALUE` = `OTA_CLUSTER`
- `ZigbeeCluster MESSAGING_CLUSTER` = `MESSAGING_CLUSTER`
- `ZigbeeCluster METERING_CLUSTER` = `METERING_CLUSTER`
- `ZigbeeCluster METER_IDENTIFICATION_CLUSTER` = `METER_IDENTIFICATION_CLUSTER`
- `ZigbeeCluster MIN_VALUE` = `BASIC_CLUSTER`
- `ZigbeeCluster MULTISTATE_INPUT_CLUSTER` = `MULTISTATE_INPUT_CLUSTER`
- `ZigbeeCluster MULTISTATE_OUTPUT_CLUSTER` = `MULTISTATE_OUTPUT_CLUSTER`
- `ZigbeeCluster MULTISTATE_VALUE_CLUSTER` = `MULTISTATE_VALUE_CLUSTER`
- `ZigbeeCluster OCCUPANCY_SENSING_CLUSTER` = `OCCUPANCY_SENSING_CLUSTER`
- `ZigbeeCluster ON_OFF_CLUSTER` = `ON_OFF_CLUSTER`
- `ZigbeeCluster ON_OFF_SWITCH_CONFIGURATION_CLUSTER` = `ON_OFF_SWITCH_CONFIGURATION_CLUSTER`
- `ZigbeeCluster OTA_CLUSTER` = `OTA_CLUSTER`
- `ZigbeeCluster POLL_CONTROL_CLUSTER` = `POLL_CONTROL_CLUSTER`
- `ZigbeeCluster POWER_CONFIGURATION_CLUSTER` = `POWER_CONFIGURATION_CLUSTER`
- `ZigbeeCluster POWER_PROFILE_CLUSTER` = `POWER_PROFILE_CLUSTER`
- `ZigbeeCluster PRESSURE_MEASUREMENT_CLUSTER` = `PRESSURE_MEASUREMENT_CLUSTER`
- `ZigbeeCluster PRICE_CLUSTER` = `PRICE_CLUSTER`
- `ZigbeeCluster PUMP_CONFIGURATION_CONTROL_CLUSTER` = `PUMP_CONFIGURATION_CONTROL_CLUSTER`
- `ZigbeeCluster RELATIVE_HUMIDITY_MEASUREMENT_CLUSTER` = `RELATIVE_HUMIDITY_MEASUREMENT_CLUSTER`
- `ZigbeeCluster RSSI_LOCATION_CLUSTER` = `RSSI_LOCATION_CLUSTER`
- `ZigbeeCluster SCENES_CLUSTER` = `SCENES_CLUSTER`
- `ZigbeeCluster SHADE_CONFIGURATION_CLUSTER` = `SHADE_CONFIGURATION_CLUSTER`
- `ZigbeeCluster SOIL_MOISTURE_MEASUREMENT` = `SOIL_MOISTURE_MEASUREMENT`
- `ZigbeeCluster TEMPERATURE_CONFIGURATION_CLUSTER` = `TEMPERATURE_CONFIGURATION_CLUSTER`
- `ZigbeeCluster TEMPERATURE_MEASUREMENT_CLUSTER` = `TEMPERATURE_MEASUREMENT_CLUSTER`
- `ZigbeeCluster THERMOSTAT_CLUSTER` = `THERMOSTAT_CLUSTER`
- `ZigbeeCluster THERMOSTAT_USER_INTERFACE_CONFIGURATION_CLUSTER` = `THERMOSTAT_USER_INTERFACE_CONFIGURATION_CLUSTER`
- `ZigbeeCluster TIME_CLUSTER` = `TIME_CLUSTER`
- `ZigbeeCluster TUNNELING_CLUSTER` = `TUNNELING_CLUSTER`
- `ZigbeeCluster WINDOW_COVERING_CLUSTER` = `WINDOW_COVERING_CLUSTER`
- `boolean __$stMC` = `false`

## hubitat.zigbee.clusters.iaszone.ZoneStatus

- Class: `hubitat.zigbee.clusters.iaszone.ZoneStatus`
- Superclass: `java.lang.Object`
- Interfaces: `groovy.lang.GroovyObject`

### Methods

- `int getAc()`
- `int getAlarm1()`
- `int getAlarm2()`
- `int getBattery()`
- `int getBatteryDefect()`
- `MetaClass getMetaClass()`
- `Object getProperty(String)`
- `int getRestoreReports()`
- `int getSupervisionReports()`
- `int getTamper()`
- `int getTest()`
- `int getTrouble()`
- `Object invokeMethod(String, Object)`
- `boolean isAcSet()`
- `boolean isAlarm1Set()`
- `boolean isAlarm2Set()`
- `boolean isBatteryDefectSet()`
- `boolean isBatterySet()`
- `boolean isRestoreReportsSet()`
- `boolean isSupervisionReportsSet()`
- `boolean isTamperSet()`
- `boolean isTestSet()`
- `boolean isTroubleSet()`
- `void setAc(int)`
- `void setAlarm1(int)`
- `void setAlarm2(int)`
- `void setBattery(int)`
- `void setBatteryDefect(int)`
- `void setMetaClass(MetaClass)`
- `void setProperty(String, Object)`
- `void setRestoreReports(int)`
- `void setSupervisionReports(int)`
- `void setTamper(int)`
- `void setTest(int)`
- `void setTrouble(int)`

### Fields

- `boolean __$stMC` = `false`
- `String copyright` = `Copyright 2016-2023 Hubitat Inc.  All Rights Reserved.`

## com.hubitat.app.DeviceWrapper

- Class: `com.hubitat.app.DeviceWrapper`
- Superclass: `groovy.lang.GroovyObjectSupport`

### Methods

- `void clearSetting(String)`
- `State currentState(String)`
- `State currentState(String, boolean)`
- `Object currentValue(String)`
- `Object currentValue(String, boolean)`
- `void deleteCurrentState(String)`
- `List events()`
- `List events(Map)`
- `List eventsBetween(Date, Date)`
- `List eventsBetween(Date, Date, Map)`
- `List eventsSince(Date)`
- `List eventsSince(Date, Map)`
- `Map fetchLastEvent()`
- `List getCapabilities()`
- `String getControllerType()`
- `List getCurrentStates()`
- `Map getData()`
- `String getDataValue(String)`
- `String getDeviceDataByName(String)`
- `String getDeviceNetworkId()`
- `Boolean getDisplayAsChild()`
- `String getDisplayName()`
- `Long getDriverId()`
- `String getDriverType()`
- `String getEndpointId()`
- `Hub getHub()`
- `String getId()`
- `Long getIdAsLong()`
- `Boolean getIsComponent()`
- `String getLabel()`
- `String getLanId()`
- `Date getLastActivity()`
- `MetaClass getMetaClass()`
- `String getName()`
- `String getNotes()`
- `Long getParentAppId()`
- `Long getParentDeviceId()`
- `Object getProperty(String)`
- `Long getRoomId()`
- `String getRoomName()`
- `Object getSetting(String)`
- `String getSettingType(String)`
- `String getStatus()`
- `List getSupportedAttributes()`
- `List getSupportedCommands()`
- `String getTypeName()`
- `Device getUnwrappedDevice()`
- `String getZigbeeId()`
- `Boolean hasAttribute(String)`
- `Boolean hasCapability(String)`
- `Boolean hasCommand(String)`
- `Object invokeMethod(String, Object)`
- `boolean isControllerMatter()`
- `boolean isControllerZWave()`
- `boolean isControllerZigbee()`
- `boolean isDisabled()`
- `boolean isLinkedDevice()`
- `boolean isRetryEnabled()`
- `boolean isSingleThreaded()`
- `State latestState(String)`
- `State latestState(String, boolean)`
- `Object latestValue(String)`
- `Object latestValue(String, boolean)`
- `Object methodMissing(String, Object)`
- `Object propertyMissing(String)`
- `void removeDataValue(String)`
- `void removeSetting(String)`
- `void sendEvent(Map)`
- `void setDeviceNetworkId(String)`
- `void setDisplayName(String)`
- `void setLabel(String)`
- `void setLanId(String)`
- `void setMetaClass(MetaClass)`
- `void setName(String)`
- `void setProperty(String, Object)`
- `List statesSince(String, Date)`
- `List statesSince(String, Date, Map)`
- `String super$1$toString()`
- `String toString()`
- `void updateDataValue(String, String)`
- `void updateSetting(String, String)`
- `void updateSetting(String, List)`
- `void updateSetting(String, Double)`
- `void updateSetting(String, Date)`
- `void updateSetting(String, Long)`
- `void updateSetting(String, Map)`
- `void updateSetting(String, Boolean)`

### Fields

- `boolean __$stMC` = `false`
- `String copyright` = `Copyright 2016-2023 Hubitat Inc.  All Rights Reserved.`

## hubitat.zigbee.SmartShield

- Class: `hubitat.zigbee.SmartShield`
- Superclass: `java.lang.Object`
- Interfaces: `groovy.lang.GroovyObject`

### Methods

- `int getClusterId()`
- `int getCommand()`
- `List getData()`
- `int getDestinationEndpoint()`
- `int getDirection()`
- `boolean getIsClusterSpecific()`
- `boolean getIsManufacturerSpecific()`
- `int getManufacturerId()`
- `int getMessageType()`
- `MetaClass getMetaClass()`
- `Integer getNumber()`
- `int getOptions()`
- `int getProfileId()`
- `Object getProperty(String)`
- `int getSenderShortId()`
- `int getSourceEndpoint()`
- `String getText()`
- `Object invokeMethod(String, Object)`
- `boolean isIsClusterSpecific()`
- `boolean isIsManufacturerSpecific()`
- `SmartShield populateFromCatchall(Map)`
- `SmartShield populateFromReadAttribute(Map)`
- `void setClusterId(int)`
- `void setCommand(int)`
- `void setData(List)`
- `void setDestinationEndpoint(int)`
- `void setDirection(int)`
- `void setIsClusterSpecific(boolean)`
- `void setIsManufacturerSpecific(boolean)`
- `void setManufacturerId(int)`
- `void setMessageType(int)`
- `void setMetaClass(MetaClass)`
- `void setNumber(Integer)`
- `void setOptions(int)`
- `void setProfileId(int)`
- `void setProperty(String, Object)`
- `void setSenderShortId(int)`
- `void setSourceEndpoint(int)`
- `void setText(String)`
- `String super$1$toString()`
- `String toString()`

### Fields

- `boolean __$stMC` = `false`
- `String copyright` = `Copyright 2016-2023 Hubitat Inc.  All Rights Reserved.`

## com.hubitat.hub.domain.State

- Class: `com.hubitat.hub.domain.State`
- Superclass: `java.lang.Object`
- Interfaces: `java.lang.Cloneable`, `com.hubitat.helper.HubitatObfuscatable`

### Methods

- `Object clone()`
- `State clone()`
- `State fromJson(Map)`
- `String getAttributeName()`
- `String getDataType()`
- `Date getDate()`
- `Long getDeviceId()`
- `Double getDoubleValue()`
- `Float getFloatValue()`
- `Long getId()`
- `Object getJsonValue()`
- `String getName()`
- `BigDecimal getNumberValue()`
- `String getStringValue()`
- `String getUnit()`
- `String getValue()`
- `void setAttributeName(String)`
- `void setDataType(String)`
- `void setDate(Date)`
- `void setDeviceId(Long)`
- `void setId(Long)`
- `void setName(String)`
- `void setUnit(String)`
- `void setValue(String)`
- `Map toJsonMap()`
- `String toString()`

### Fields

- `String copyright` = `Copyright 2016-2023 Hubitat Inc.  All Rights Reserved.`

## com.hubitat.hub.domain.Device

- Class: `com.hubitat.hub.domain.Device`
- Superclass: `java.lang.Object`
- Interfaces: `java.lang.Cloneable`, `com.hubitat.helper.HubitatObfuscatable`

### Methods

- `Device clone()`
- `Object clone()`
- `State currentState(String)`
- `State currentState(String, boolean)`
- `Object currentValue(String)`
- `Object currentValue(String, boolean)`
- `boolean equals(Object)`
- `Long getCompatibleDeviceId()`
- `String getControllerType()`
- `Date getCreateTime()`
- `Map getCurrentStates()`
- `Map getData()`
- `String getDataJson()`
- `String getDataValue(String)`
- `String getDefaultCurrentState()`
- `String getDefaultIcon()`
- `String getDeviceDataByName(String)`
- `Long getDeviceId()`
- `String getDeviceNetworkId()`
- `String getDeviceTypeClassLocation()`
- `Long getDeviceTypeId()`
- `String getDeviceTypeName()`
- `String getDeviceTypeNamespace()`
- `String getDeviceTypeReadableType()`
- `boolean getDeviceTypeSingleThreaded()`
- `String getDeviceTypeType()`
- `boolean getDisabled()`
- `Boolean getDisplayAsChild()`
- `String getDisplayAttributes()`
- `String getDisplayName()`
- `String getDriverType()`
- `String getEndpointId()`
- `Long getGroupId()`
- `String getGroupName()`
- `Hub getHub()`
- `Long getHubId()`
- `String getHubName()`
- `Long getId()`
- `Boolean getIsComponent()`
- `String getLabel()`
- `String getLanId()`
- `Date getLastActivityTime()`
- `Long getLocationId()`
- `String getLocationName()`
- `Integer getMaxEvents()`
- `Integer getMaxStates()`
- `boolean getMeshEnabled()`
- `boolean getMeshFullSync()`
- `String getName()`
- `String getNotes()`
- `Long getParentAppId()`
- `Long getParentDeviceId()`
- `Object getProperty(String)`
- `String getRemoteDeviceUrl()`
- `Long getRoomId()`
- `String getRoomName()`
- `Long getSpammyThreshold()`
- `String getStatus()`
- `String getTags()`
- `Date getUpdateTime()`
- `Long getVersion()`
- `String getZigbeeId()`
- `boolean hasCapability(String)`
- `boolean hasParentDevice()`
- `int hashCode()`
- `boolean isBluetooth()`
- `boolean isDeviceTypePopulated()`
- `boolean isDisabled()`
- `boolean isHomeKitCompatible()`
- `boolean isLinkedAndDisabled()`
- `boolean isLinkedDevice()`
- `boolean isMatter()`
- `boolean isMeshEnabled()`
- `boolean isMeshFullSync()`
- `boolean isMeshSelectionEnabled()`
- `boolean isNetwork()`
- `boolean isOrphan()`
- `boolean isRetryEnabled()`
- `boolean isRoomAssigned()`
- `boolean isShowOnHome()`
- `boolean isSystemDeviceType()`
- `boolean isVirtual()`
- `boolean isZWave()`
- `boolean isZigbee()`
- `Object latestValue(String)`
- `void populateCurrentStatesFromList(List)`
- `void removeDataValue(String)`
- `void resetCurrentStates()`
- `void setCompatibleDeviceId(Long)`
- `void setControllerType(String)`
- `void setCreateTime(Date)`
- `void setCurrentStates(Map)`
- `void setData(Map)`
- `void setDataJson(String)`
- `void setDefaultCurrentState(String)`
- `void setDefaultIcon(String)`
- `void setDeviceNetworkId(String)`
- `void setDeviceTypeId(Long)`
- `void setDeviceTypeName(String)`
- `void setDeviceTypeReadableType(String)`
- `void setDisabled(boolean)`
- `void setDisplayAsChild(Boolean)`
- `void setDisplayAttributes(String)`
- `void setDriverType(String)`
- `void setEndpointId(String)`
- `void setGroupId(Long)`
- `void setGroupName(String)`
- `void setHubId(Long)`
- `void setId(Long)`
- `void setIsComponent(Boolean)`
- `void setLabel(String)`
- `void setLanId(String)`
- `void setLastActivityTime(Date)`
- `void setMaxEvents(Integer)`
- `void setMaxStates(Integer)`
- `void setMeshEnabled(boolean)`
- `void setMeshFullSync(boolean)`
- `void setName(String)`
- `void setNotes(String)`
- `void setParentAppId(Long)`
- `void setParentDeviceId(Long)`
- `void setRetryEnabled(boolean)`
- `void setRoomId(Long)`
- `void setShowOnHome(boolean)`
- `void setSpammyThreshold(Long)`
- `void setTags(List)`
- `void setTags(String)`
- `void setUpdateTime(Date)`
- `void setVersion(Long)`
- `void setZigbeeId(String)`
- `Map toMap()`
- `String toString()`
- `void updateDataValue(String, String)`

### Fields

- `String copyright` = `Copyright 2016-2023 Hubitat Inc.  All Rights Reserved.`

## com.hubitat.hub.domain.Hub

- Class: `com.hubitat.hub.domain.Hub`
- Superclass: `java.lang.Object`
- Interfaces: `groovy.lang.GroovyObject`

### Methods

- `void addData(Map)`
- `Date getCreateTime()`
- `Map getData()`
- `Map getDataSorted()`
- `String getDataValue(String)`
- `String getFirmwareVersionString()`
- `String getHardwareID()`
- `Long getId()`
- `Date getLastActivityTime()`
- `String getLocalIP()`
- `String getLocalSrvPortTCP()`
- `Long getLocationId()`
- `MetaClass getMetaClass()`
- `String getName()`
- `Object getProperty(String)`
- `String getType()`
- `Date getUpdateTime()`
- `BigInteger getUptime()`
- `Long getVersion()`
- `String getZigbeeEui()`
- `String getZigbeeId()`
- `Object invokeMethod(String, Object)`
- `Boolean isBatteryInUse()`
- `void setCreateTime(Date)`
- `void setData(Map)`
- `void setId(Long)`
- `void setLastActivityTime(Date)`
- `void setLocationId(Long)`
- `void setMetaClass(MetaClass)`
- `void setName(String)`
- `void setProperty(String, Object)`
- `void setUpdateTime(Date)`
- `void setVersion(Long)`
- `String super$1$toString()`
- `Map toMap()`
- `String toString()`
- `boolean updateSystemTime(Date)`

### Fields

- `boolean __$stMC` = `false`
- `String copyright` = `Copyright 2016-2023 Hubitat Inc.  All Rights Reserved.`

---

_8 classes inspected (cap 80)._
