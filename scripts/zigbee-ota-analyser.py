#!/usr/bin/env python3
"""
Zigbee OTA Traffic Analyzer

Analyzes OTA (cluster 0x0019) messages from Hubitat zigbeeLogsocket captures.

Usage:
    python3 zigbee-ota-analyzer.py <logfile> [options]

Options:
    --dni <id>              Filter by device network ID (hex, e.g., 78E0)
    --manufacturer <code>   Filter by manufacturer code (hex, e.g., 1233)
    --device-id <id>        Filter by Hubitat device ID (decimal)
    --device-name <n>       Filter by device name (partial match)
    --output, -o <file>     Write filtered log entries to file
    --csv                   Output as CSV instead of table
"""

import json
import sys
import argparse
from collections import defaultdict
from datetime import datetime
import statistics


def parse_ota_message(line):
    """Parse a JSON log line and extract OTA-relevant fields."""
    try:
        data = json.loads(line.strip())
    except json.JSONDecodeError:
        return None

    if data.get('clusterId') != '0019':
        return None

    payload = data.get('payload', [])

    # Extract manufacturer code (bytes 4-5, little endian)
    if len(payload) >= 6:
        mfr_code = (payload[5] + payload[4]).upper()
    else:
        mfr_code = 'unknown'

    # Extract command type (byte 2)
    cmd_type = payload[2] if len(payload) > 2 else 'unknown'

    return {
        'name': data.get('name', 'unknown'),
        'id': data.get('id'),
        'device_id': data.get('deviceId'),
        'lqi': data.get('lastHopLqi'),
        'rssi': data.get('lastHopRssi'),
        'time': data.get('time'),
        'mfr_code': mfr_code,
        'cmd_type': cmd_type,
        'sequence': data.get('sequence'),
        'payload': payload,
    }


def analyze_log(filepath, mfr_filter=None, device_id_filter=None, device_name_filter=None, dni_filter=None, output_file=None):
    """Analyze log file and return device statistics and time range."""
    devices = defaultdict(lambda: {
        'count': 0,
        'lqis': [],
        'rssis': [],
        'mfr_code': None,
        'device_id': None,
        'network_id': None,
        'times': [],
    })

    all_times = []
    output_handle = open(output_file, 'w') if output_file else None

    with open(filepath, 'r') as f:
        for line in f:
            msg = parse_ota_message(line)
            if not msg:
                continue

            # Apply filters
            if mfr_filter and msg['mfr_code'].upper() != mfr_filter.upper():
                continue
            if device_id_filter and msg['device_id'] != device_id_filter:
                continue
            if device_name_filter and device_name_filter.lower() not in msg['name'].lower():
                continue
            if dni_filter and msg['id'] != dni_filter:
                continue

            # Write matching line to output file
            if output_handle:
                output_handle.write(line)

            name = msg['name']
            devices[name]['count'] += 1
            devices[name]['lqis'].append(msg['lqi'])
            devices[name]['rssis'].append(msg['rssi'])
            devices[name]['mfr_code'] = msg['mfr_code']
            devices[name]['device_id'] = msg['device_id']
            devices[name]['network_id'] = msg['id']
            devices[name]['times'].append(msg['time'])
            if msg['time']:
                all_times.append(msg['time'])

    if output_handle:
        output_handle.close()

    time_range = (min(all_times), max(all_times)) if all_times else (None, None)
    return devices, time_range


def print_table(devices, time_range=None, filters=None):
    """Print results as formatted table."""
    sorted_devices = sorted(devices.items(), key=lambda x: x[1]['count'], reverse=True)

    print("Zigbee OTA Query Analysis")
    print("=" * 103)

    if time_range and time_range[0] and time_range[1]:
        try:
            t1 = datetime.strptime(time_range[0], "%Y-%m-%d %H:%M:%S.%f")
            t2 = datetime.strptime(time_range[1], "%Y-%m-%d %H:%M:%S.%f")
            duration = t2 - t1
            hours, remainder = divmod(int(duration.total_seconds()), 3600)
            minutes, seconds = divmod(remainder, 60)
            print(f"Time range: {time_range[0]} to {time_range[1]} ({hours}h {minutes}m)")
        except ValueError:
            print(f"Time range: {time_range[0]} to {time_range[1]}")

    if filters:
        filter_parts = []
        if filters.get('manufacturer'):
            mfr_name = KNOWN_MANUFACTURERS.get(filters['manufacturer'].upper(), 'Unknown')
            filter_parts.append(f"manufacturer={filters['manufacturer']} ({mfr_name})")
        if filters.get('dni'):
            filter_parts.append(f"dni=0x{filters['dni']:04X}")
        if filters.get('device_id'):
            filter_parts.append(f"device_id={filters['device_id']}")
        if filters.get('device_name'):
            filter_parts.append(f"name contains '{filters['device_name']}'")
        if filter_parts:
            print(f"Filters: {', '.join(filter_parts)}")

    print()
    print(f"{'Device Label':<35} {'Mfr Code':<10} {'Manufacturer':<28} {'Count':>7} {'Med LQI':>8} {'Med RSSI':>9}")
    print("-" * 103)

    total = 0
    for name, data in sorted_devices:
        median_lqi = statistics.median(data['lqis']) if data['lqis'] else 0
        median_rssi = statistics.median(data['rssis']) if data['rssis'] else 0
        mfr_name = KNOWN_MANUFACTURERS.get(data['mfr_code'], 'Unknown')
        print(f"{name:<35} {data['mfr_code']:<10} {mfr_name:<28} {data['count']:>7} {median_lqi:>8.0f} {median_rssi:>9.0f}")
        total += data['count']

    print("-" * 103)
    print(f"{'TOTAL':<35} {'':<10} {'':<28} {total:>7}")


def print_csv(devices):
    """Print results as CSV."""
    print("Device Label,Mfr Code,Manufacturer,Count,Median LQI,Median RSSI,Network ID,Device ID")

    sorted_devices = sorted(devices.items(), key=lambda x: x[1]['count'], reverse=True)

    for name, data in sorted_devices:
        median_lqi = statistics.median(data['lqis']) if data['lqis'] else 0
        median_rssi = statistics.median(data['rssis']) if data['rssis'] else 0
        mfr_name = KNOWN_MANUFACTURERS.get(data['mfr_code'], 'Unknown')
        network_id_hex = f"0x{data['network_id']:04X}" if data['network_id'] else ''
        print(f'"{name}",{data["mfr_code"]},{mfr_name},{data["count"]},{median_lqi:.0f},{median_rssi:.0f},{network_id_hex},{data["device_id"]}')


def main():
    parser = argparse.ArgumentParser(description='Analyze Zigbee OTA traffic from websocket logs')
    parser.add_argument('logfile', help='Path to zigbee log file')
    parser.add_argument('--manufacturer', '-m', help='Filter by manufacturer code (hex, e.g., 1233)')
    parser.add_argument('--device-id', '-d', type=int, help='Filter by Hubitat device ID (decimal)')
    parser.add_argument('--device-name', '-n', help='Filter by device name (partial match)')
    parser.add_argument('--dni', help='Filter by device network ID (hex, e.g., 78E0 or 0x78E0)')
    parser.add_argument('--output', '-o', help='Write filtered log entries to this file')
    parser.add_argument('--csv', action='store_true', help='Output as CSV')

    args = parser.parse_args()

    # Parse DNI from hex to decimal
    dni_filter = None
    if args.dni:
        dni_hex = args.dni.upper().replace('0X', '')
        dni_filter = int(dni_hex, 16)

    devices, time_range = analyze_log(
        args.logfile,
        mfr_filter=args.manufacturer,
        device_id_filter=args.device_id,
        device_name_filter=args.device_name,
        dni_filter=dni_filter,
        output_file=args.output,
    )

    if not devices:
        print("No OTA messages found matching criteria.", file=sys.stderr)
        sys.exit(1)

    if args.output:
        print(f"Wrote filtered logs to: {args.output}", file=sys.stderr)

    filters = {
        'manufacturer': args.manufacturer,
        'dni': dni_filter,
        'device_id': args.device_id,
        'device_name': args.device_name,
    }

    if args.csv:
        print_csv(devices)
    else:
        print_table(devices, time_range, filters)


# =============================================================================
# Manufacturer Code Database
# Source: https://github.com/Koenkk/zigbee-herdsman/blob/master/src/zspec/zcl/definition/manufacturerCode.ts
# =============================================================================
KNOWN_MANUFACTURERS = {
    '0000': 'Matter Standard',
    '0001': 'Panasonic',
    '0002': 'Sony',
    '0003': 'Samsung',
    '0004': 'Philips RF4CE',
    '0005': 'Freescale RF4CE',
    '0006': 'OKI Semiconductors RF4CE',
    '0007': 'Texas Instruments',
    '007B': 'Perenio (custom)',
    '1000': 'Cirronet',
    '1001': 'Chipcon',
    '1002': 'Ember',
    '1003': 'NTS',
    '1004': 'Freescale',
    '1005': 'IP-Com',
    '1006': 'San Juan Software',
    '1007': 'TUV',
    '1008': 'Integration',
    '1009': 'BM SpA',
    '100A': 'Awarepoint',
    '100B': 'Signify (Philips)',
    '100C': 'Luxoft',
    '100D': 'Korwin',
    '100E': 'One RF Technology',
    '100F': 'Software Technologies Group',
    '1010': 'Telegesis',
    '1011': 'Visonic',
    '1012': 'Insta',
    '1013': 'Atalum',
    '1014': 'Atmel',
    '1015': 'Develco',
    '1016': 'Honeywell',
    '1017': 'RadioPulse',
    '1018': 'Renesas',
    '1019': 'Xanadu Wireless',
    '101A': 'NEC Engineering',
    '101B': 'Yamatake',
    '101C': 'Tendril Networks',
    '101D': 'Assa Abloy',
    '101E': 'MaxStream',
    '101F': 'Neurocom',
    '1020': 'III (Taiwan)',
    '1021': 'Legrand',
    '1022': 'iControl',
    '1023': 'Raymarine',
    '1024': 'LS Research',
    '1025': 'Onity',
    '1026': 'Mono Products',
    '1027': 'RF Technologies',
    '1028': 'Itron',
    '1029': 'Tritech',
    '102A': 'Embedit',
    '102B': 'S3C',
    '102C': 'Siemens',
    '102D': 'Mindtech',
    '102E': 'LG Electronics',
    '102F': 'Mitsubishi Electric',
    '1030': 'Johnson Controls',
    '1031': 'Secure Meters UK',
    '1032': 'Knick',
    '1033': 'Viconics',
    '1034': 'Flexipanel',
    '1035': 'Piasim',
    '1036': 'Trane',
    '1037': 'NXP',
    '1038': 'Living Independently',
    '1039': 'AlertMe',
    '103A': 'Daintree',
    '103B': 'Aiji System',
    '103C': 'Telecom Italia',
    '103D': 'Mikrokrets',
    '103E': 'OKI Semiconductor',
    '103F': 'Newport Electronics',
    '1040': 'Control4',
    '1041': 'STMicroelectronics',
    '1042': 'Ad-Sol Nissin',
    '1043': 'DCSI',
    '1044': 'France Telecom',
    '1045': 'muNet',
    '1046': 'Autani',
    '1047': 'Colorado vNet',
    '1048': 'Aerocomm',
    '1049': 'Silicon Labs',
    '104A': 'Inncom',
    '104B': 'Cooper Power',
    '104C': 'Synapse',
    '104D': 'Fisher-Pierce',
    '104E': 'Centralite',
    '104F': 'Crane Wireless',
    '1050': 'Mobilarm',
    '1051': 'iMonitor',
    '1052': 'Bartech',
    '1053': 'Meshnetics',
    '1054': 'LS Industrial',
    '1055': 'Cason',
    '1056': 'Wireless Glue',
    '1057': 'Elster',
    '1058': 'SMS Tecnologia',
    '1059': 'Onset Computer',
    '105A': 'Riga Development',
    '105B': 'Energate',
    '105C': 'ConMed Linvatec',
    '105D': 'PowerMand',
    '105E': 'Schneider Electric',
    '105F': 'Eaton',
    '1060': 'Telular',
    '1061': 'Delphi Medical',
    '1062': 'EpiSensor',
    '1063': 'Landis+Gyr',
    '1064': 'Kaba Group',
    '1065': 'Shure',
    '1066': 'Comverge',
    '1067': 'DBS Lodging',
    '1068': 'Energy Aware',
    '1069': 'Hidalgo',
    '106A': 'Air2App',
    '106B': 'AMX',
    '106C': 'EDMI',
    '106D': 'Cyan',
    '106E': 'System SPA',
    '106F': 'Telit',
    '1070': 'Kaga Electronics',
    '1071': 'Astrel Group',
    '1072': 'Certicom',
    '1073': 'Gridpoint',
    '1074': 'Profile Systems',
    '1075': 'Compacta',
    '1076': 'Freestyle Technology',
    '1077': 'Alektrona',
    '1078': 'Computime',
    '1079': 'Remote Technologies',
    '107A': 'Wavecom',
    '107B': 'Energy Optimizers',
    '107C': 'GE',
    '107D': 'Jetlun',
    '107E': 'Cipher Systems',
    '107F': 'Corporate Systems Eng',
    '1080': 'Ecobee',
    '1081': 'SMK',
    '1082': 'Meshworks Wireless',
    '1083': 'Ellips',
    '1084': 'Secure Electrans',
    '1085': 'CEDO',
    '1086': 'Toshiba',
    '1087': 'Digi International',
    '1088': 'Ubilogix',
    '1089': 'Echelon',
    '1090': 'Green Energy Options',
    '1091': 'Silver Spring Networks',
    '1092': 'Black & Decker',
    '1093': 'Aztech Associates',
    '1094': 'A&D Co',
    '1095': 'Rainforest Automation',
    '1096': 'Carrier Electronics',
    '1097': 'SyChip/Murata',
    '1098': 'OpenPeak',
    '1099': 'PassiveSystems',
    '109A': 'MMB Research',
    '109B': 'Leviton',
    '109C': 'KEPCO',
    '109D': 'Comcast',
    '109E': 'NEC Electronics',
    '109F': 'Netvox',
    '10A0': 'U-Control',
    '10A1': 'Embedia',
    '10A2': 'Sensus',
    '10A3': 'SunRise Technologies',
    '10A4': 'Memtech',
    '10A5': 'Freebox',
    '10A6': 'M2 Labs',
    '10A7': 'British Gas',
    '10A8': 'Sentec',
    '10A9': 'Navetas',
    '10AA': 'Lightspeed Technologies',
    '10AB': 'OKI Electric',
    '10AC': 'S.I. Sistemas',
    '10AD': 'Dometic',
    '10AE': 'Alps',
    '10AF': 'EnergyHub',
    '10B0': 'Kamstrup',
    '10B1': 'EchoStar',
    '10B2': 'EnerNOC',
    '10B3': 'Eltav',
    '10B4': 'Belkin',
    '10B5': 'XstreamHD',
    '10B6': 'Saturn South',
    '10B7': 'GreenTrap',
    '10B8': 'SmartSynch',
    '10B9': 'Nyce Control',
    '10BA': 'ICM Controls',
    '10BB': 'Millennium Electronics',
    '10BC': 'Motorola',
    '10BD': 'Emerson White-Rodgers',
    '10BE': 'Radio Thermostat',
    '10BF': 'Omron',
    '10C0': 'GiiNii',
    '10C1': 'Fujitsu General',
    '10C2': 'Peel Technologies',
    '10C3': 'Accent',
    '10C4': 'ByteSnap',
    '10C5': 'NEC Tokin',
    '10C6': 'G4S Justice Services',
    '10C7': 'Trilliant Networks',
    '10C8': 'Electrolux Italia',
    '10C9': 'OnZo',
    '10CA': 'EnTek Systems',
    '10CB': 'Philips',
    '10CD': 'Indesit',
    '10CE': 'ThinkEco',
    '10CF': '2D2C',
    '10D0': 'Qorvo',
    '10D1': 'InterCel',
    '10D2': 'LG Electronics 2',
    '10D3': 'Mitsumi Electric',
    '10D4': 'Mitsumi Electric 2',
    '10D5': 'ZMDI',
    '10D6': 'Nest Labs',
    '10D7': 'Exegin Technologies',
    '10D8': 'Honeywell 2',
    '10D9': 'Takahata Precision',
    '10DA': 'Sumitomo Electric',
    '10DB': 'GE Energy',
    '10DC': 'GE Appliances',
    '10DD': 'Radiocrafts',
    '10DE': 'Ceiva',
    '10DF': 'TEC&CO',
    '10E0': 'Chameleon Technology',
    '10E1': 'Samsung 2',
    '10E2': 'Ruwido Austria',
    '10E3': 'Huawei',
    '10E4': 'Huawei 2',
    '10E5': 'Greenwave Reality',
    '10E6': 'BGlobal Metering',
    '10E7': 'Mindteck',
    '10E8': 'Ingersoll Rand',
    '10E9': 'Dius Computing',
    '10EA': 'Embedded Automation',
    '10EB': 'ABB',
    '10EC': 'Sony 2',
    '10ED': 'Genus Power',
    '10EE': 'Universal Electronics',
    '10EF': 'Universal Electronics 2',
    '10F0': 'Metrum Technologies',
    '10F1': 'Cisco',
    '10F2': 'Ubisys',
    '10F3': 'Consert',
    '10F4': 'Crestron',
    '10F5': 'Enphase Energy',
    '10F6': 'Invensys Controls',
    '10F7': 'Mueller Systems',
    '10F8': 'AAC Technologies',
    '10F9': 'U-NEXT',
    '10FA': 'Steelcase',
    '10FB': 'Telematics Wireless',
    '10FC': 'Samil Power',
    '10FD': 'Pace',
    '10FE': 'Osborne Coinage',
    '10FF': 'Powerwatch',
    '1100': 'Candeled',
    '1101': 'FlexGrid',
    '1102': 'Humax',
    '1103': 'Universal Devices',
    '1104': 'Advanced Energy',
    '1105': 'BEGA',
    '1106': 'Brunel University',
    '1107': 'Panasonic R&D Singapore',
    '1108': 'eSystems Research',
    '1109': 'Panamax',
    '110A': 'SmartThings',
    '110B': 'EM-Lite',
    '110C': 'OSRAM Sylvania',
    '110D': '2 Save Energy',
    '110E': 'Planet Innovation',
    '110F': 'Ambient Devices',
    '1110': 'Profalux',
    '1111': 'Billion Electric (BEC)',
    '1112': 'Embertec',
    '1113': 'IT Watchdogs',
    '1114': 'Reloc',
    '1115': 'Intel',
    '1116': 'Trend Electronics',
    '1117': 'Moxa',
    '1118': 'QEES',
    '1119': 'SAYME Wireless',
    '111A': 'Pentair Aquatic',
    '111B': 'Orbit Irrigation',
    '111C': 'California Eastern Labs',
    '111D': 'Comcast 2',
    '111E': 'IDT Technology',
    '111F': 'Pixela',
    '1120': 'TiVo',
    '1121': 'Fidure',
    '1122': 'Marvell Semiconductor',
    '1123': 'Wasion Group',
    '1124': 'Jasco Products',
    '1125': 'Shenzhen Kaifa',
    '1126': 'NetComm Wireless',
    '1127': 'Define Instruments',
    '1128': 'In Home Displays',
    '1129': 'Miele',
    '112A': 'Televes',
    '112B': 'Labelec',
    '112C': 'China Electronics Standard',
    '112D': 'Vectorform',
    '112E': 'Busch-Jaeger',
    '112F': 'Redpine Signals',
    '1130': 'Bridges Electronic',
    '1131': 'Sercomm',
    '1132': 'WSH (wirsindheller)',
    '1133': 'Bosch Security',
    '1134': 'EZEX',
    '1135': 'Dresden Elektronik',
    '1136': 'Meazon',
    '1137': 'Crow Electronic',
    '1138': 'Harvard Engineering',
    '1139': 'Andson (Beijing)',
    '113A': 'Adhoco',
    '113B': 'Waxman Consumer',
    '113C': 'Owon Technology',
    '113D': 'Hitron Technologies',
    '113E': 'Scemtec',
    '113F': 'Webee',
    '1140': 'Grid2Home',
    '1141': 'Telink Micro',
    '1142': 'Jasmine Systems',
    '1143': 'Bidgely',
    '1144': 'Lutron',
    '1145': 'IJENKO',
    '1146': 'Starfield Electronic',
    '1147': 'TCP',
    '1148': 'Rogers Communications',
    '1149': 'Cree',
    '114A': 'Robert Bosch LLC',
    '114B': 'Ibis Networks',
    '114C': 'Quirky',
    '114D': 'Efergy Technologies',
    '114E': 'Smartlabs',
    '114F': 'Everspring Industry',
    '1150': 'Swann Communications',
    '1151': 'Soneter',
    '1152': 'Samsung SDS',
    '1153': 'Uniband Electronic',
    '1154': 'Accton Technology',
    '1155': 'Bosch Thermotechnik',
    '1156': 'Wincor Nixdorf',
    '1157': 'Ohsung Electronics',
    '1158': 'Zen Within',
    '1159': 'Tech4Home',
    '115A': 'Nanoleaf',
    '115B': 'Keen Home',
    '115C': 'Poly-Control',
    '115D': 'Eastfield Lighting',
    '115E': 'IP Datatel',
    '115F': 'Lumi United (Aqara)',
    '1160': 'Sengled',
    '1161': 'Remote Solution',
    '1162': 'ABB Genway Xiamen',
    '1163': 'Zhejiang Rexense',
    '1164': 'ForEE Technology',
    '1165': 'Open Access Technology',
    '1166': 'Innr Lighting',
    '1167': 'Techworld Industries',
    '1168': 'Leedarson Lighting',
    '1169': 'Arzel Zoning',
    '116A': 'Holley Technology',
    '116B': 'Beldon Technologies',
    '116C': 'Flextronics',
    '116D': 'Shenzhen Meian',
    '116E': 'Lowes',
    '116F': 'Sigma Connectivity',
    '1171': 'Wulian',
    '1172': 'Plugwise',
    '1173': 'Titan Products',
    '1174': 'Ecospectral',
    '1175': 'D-Link',
    '1176': 'Technicolor Home USA',
    '1177': 'Opple Lighting',
    '1178': 'Wistron NeWeb',
    '1179': 'QMotion Shades',
    '117A': 'Insta GmbH',
    '117B': 'Shanghai Vancount',
    '117C': 'IKEA',
    '117D': 'RT-RK',
    '117E': 'Shenzhen Feibit',
    '117F': 'EuControls',
    '1180': 'Telkonet',
    '1181': 'Thermal Solution Resources',
    '1182': 'POMCube',
    '1183': 'Ei Electronics',
    '1184': 'Optoga',
    '1185': 'Stelpro',
    '1186': 'Lynxus Technologies',
    '1187': 'Semiconductor Components',
    '1188': 'TP-Link',
    '1189': 'LEDVANCE',
    '118A': 'Nortek',
    '118B': 'iRevo (Assa Abloy Korea)',
    '118C': 'Midea',
    '118D': 'ZF Friedrichshafen',
    '118E': 'Checkit',
    '118F': 'Aclara',
    '1190': 'Nokia',
    '1191': 'Goldcard High-Tech',
    '1192': 'George Wilson Industries',
    '1193': 'Easy Saver',
    '1194': 'ZTE',
    '1195': 'Arris',
    '1196': 'Reliance Big TV',
    '1197': 'Insight Energy (Powerley)',
    '1198': 'Thomas Research (Hubbell)',
    '1199': 'Li Seng Technology',
    '119A': 'System Level Solutions',
    '119B': 'Matrix Labs',
    '119C': 'Sinopé Technologies',
    '119D': 'Jiuzhou Greeble',
    '119E': 'Guangzhou Lanvee',
    '119F': 'Venstar',
    '1200': 'SLV',
    '1201': 'Halo Smart Labs',
    '1202': 'Scout Security',
    '1203': 'Alibaba China',
    '1204': 'Resolution Products',
    '1205': 'Smartlok',
    '1206': 'Lux Products',
    '1207': 'Vimar',
    '1208': 'Universal Lighting Tech',
    '1209': 'Robert Bosch GmbH',
    '120A': 'Accenture',
    '120B': 'Heiman Technology',
    '120C': 'Shenzhen Homa',
    '120D': 'Vision Electronics',
    '120E': 'Lenovo',
    '120F': 'Presciense R&D',
    '1210': 'Shenzhen Seastar',
    '1211': 'Sensative',
    '1212': 'SolarEdge',
    '1213': 'Zipato',
    '1214': 'China Fire & Security (iHorn)',
    '1215': 'Quby',
    '1216': 'Hangzhou Roombanker',
    '1217': 'Amazon Lab126',
    '1218': 'Paulmann Licht',
    '1219': 'Shenzhen Orvibo',
    '121A': 'TCI Telecommunications',
    '121B': 'Müller-Licht',
    '121C': 'Aurora Limited',
    '121D': 'SmartDCC',
    '121E': 'Shanghai Umeinfo',
    '121F': 'CarbonTrack',
    '1220': 'Somfy',
    '1221': 'Viessmann Elektronik',
    '1222': 'Hildebrand Technology',
    '1223': 'Onkyo Technology',
    '1224': 'Shenzhen Sunricher',
    '1225': 'Xiu Xiu Technology',
    '1226': 'Zumtobel Group',
    '1227': 'Shenzhen Kaadas',
    '1228': 'Shanghai Xiaoyan',
    '1229': 'Cypress Semiconductor',
    '122A': 'XAL GmbH',
    '122B': 'Inergy Systems',
    '122C': 'Alfred Kärcher',
    '122D': 'Adurolight',
    '122E': 'Groupe Muller',
    '122F': 'V-Mark Enterprises',
    '1230': 'Lead Energy',
    '1231': 'Ultimate IoT (Henan)',
    '1232': 'Axxess Industries',
    '1233': 'ThirdReality',
    '1234': 'DSR Corporation',
    '1235': 'Guangzhou Vensi',
    '1236': 'Schlage Lock (Allegion)',
    '1237': 'Net2Grid',
    '1238': 'Airam Electric',
    '1239': 'Immax WPB CZ',
    '123A': 'ZIV Automation',
    '123B': 'Hangzhou Imagic',
    '123C': 'Xiamen Leelen',
    '123D': 'Overkiz',
    '123E': 'Flonidan',
    '123F': 'HDL Automation',
    '1240': 'Ardomus Networks',
    '1241': 'Samjin',
    '1242': 'FireAngel Safety',
    '1243': 'Indra Sistemas',
    '1244': 'Shenzhen JBT',
    '1245': 'GE Lighting (Current)',
    '1246': 'Danfoss',
    '1247': 'Niviss PHP',
    '1248': 'Shenzhen Fengliyuan',
    '1249': 'Nexelec',
    '124A': 'Sichuan Behome',
    '124B': 'Fujian Star-net',
    '124C': 'Toshiba Visual Solutions',
    '124D': 'Latchable',
    '124E': 'L&S Deutschland',
    '124F': 'Gledopto',
    '1250': 'The Home Depot',
    '1251': 'Neonlite Distribution',
    '1252': 'Arlo Technologies',
    '1253': 'Xingluo Technology',
    '1254': 'Simon Electric China',
    '1255': 'Hangzhou Greatstar',
    '1256': 'Sequentric Energy',
    '1257': 'Solum',
    '1258': 'Eaglerise Electric',
    '1259': 'Fantem Technologies',
    '125A': 'Yunding Network (Beijing)',
    '125B': 'Atlantic Group',
    '125C': 'Xiamen Intretech',
    '125D': 'Tuya',
    '125E': 'DNAKE (Xiamen)',
    '125F': 'Niko',
    '1260': 'Emporia Energy',
    '1261': 'Sikom',
    '1262': 'Axis Labs',
    '1263': 'Current Products',
    '1264': 'Metersit',
    '1265': 'Hornbach Baumarkt',
    '1266': 'Diceworld',
    '1267': 'ARC Technology',
    '1268': 'Hangzhou Konke',
    '1269': 'Salto Systems',
    '126A': 'Shenzhen Shyugj',
    '126B': 'Brayden Automation',
    '126C': 'Environexus',
    '126D': 'Eltra',
    '126E': 'Xiaomi Communications',
    '126F': 'Shanghai Shuncom',
    '1270': 'Voltalis',
    '1271': 'Feelux',
    '1272': 'SmartPlus',
    '1273': 'Halemeier',
    '1274': 'Trust International',
    '1275': 'Duke Energy',
    '1276': 'Calix',
    '1277': 'Adeo',
    '1278': 'Connected Response',
    '1279': 'StroyEnergoKom',
    '127A': 'Lumitech Lighting',
    '127B': 'Verdant Environmental',
    '127C': 'Alfred International',
    '127D': 'Sansi LED',
    '127E': 'Mindtree',
    '127F': 'Nordic Semiconductor',
    '1280': 'Siterwell Electronics',
    '1281': 'Briloner Leuchten',
    '1282': 'Shenzhen SEI Technology',
    '1283': 'Copper Labs',
    '1284': 'Delta Dore',
    '1285': 'Hager Group',
    '1286': 'Shenzhen Coolkit (Sonoff)',
    '1287': 'Hangzhou Sky-Lighting',
    '1288': 'E.ON',
    '1289': 'Lidl',
    '128A': 'Sichuan Changhong',
    '128B': 'NodOn',
    '128C': 'Jiangxi Innotech',
    '128D': 'Mercator',
    '128E': 'Beijing Ruying',
    '128F': 'Eglo Leuchten',
    '1290': 'Pietro Fiorentini',
    '1291': 'Zehnder Group',
    '1292': 'BRK Brands',
    '1293': 'Askey Computer',
    '1294': 'PassiveBolt',
    '1295': 'AVM (Fritz)',
    '1296': 'Ningbo Suntech',
    '1297': 'Stello',
    '1298': 'Vivint Smart Home',
    '1299': 'Namron',
    '129A': 'Rademacher',
    '129B': 'OMO Systems',
    '129C': 'Siglis',
    '129D': 'Imhotep Creation',
    '129E': 'iCasa',
    '129F': 'Level Home',
    '1300': 'TIS Control',
    '1301': 'Radisys India',
    '1302': 'Veea',
    '1303': 'Fell Technology',
    '1304': 'Sowilo Design',
    '1305': 'Lexi Devices',
    '1306': 'LIFX',
    '1307': 'Grundfos',
    '1308': 'Sourcing & Creation',
    '1309': 'Kraken Technologies',
    '130A': 'Eve Systems',
    '130B': 'Lite-On Technology',
    '130C': 'Focalcrest',
    '130D': 'Bouffalo Lab',
    '130E': 'Wyze Labs',
    '130F': 'Z-Wave Europe',
    '1310': 'Aeotec',
    '1311': 'NGSTB',
    '1312': 'Qingdao Yeelink (Yeelight)',
    '1313': 'E-Smart Home',
    '1314': 'Fibaro',
    '1315': 'Prolitech',
    '1316': 'Pankore IC',
    '1317': 'Logitech',
    '1318': 'Piaro',
    '1319': 'Mitsubishi Electric US',
    '131A': 'Resideo Technologies',
    '131B': 'Espressif Systems',
    '131C': 'Hella',
    '131D': 'Geberit International',
    '131E': 'Came SpA',
    '131F': 'Guangzhou Elite Education',
    '1320': 'PhyPlus Microelectronics',
    '1321': 'Shenzhen Sonoff (ITEAD)',
    '1322': 'Safe4 Security',
    '1323': 'Shanghai MXCHIP',
    '1324': 'HDC i-Controls',
    '1325': 'Zuma Array',
    '1326': 'Decelect',
    '1327': 'Mill International',
    '1328': 'HomeWizard',
    '1329': 'Shenzhen Topband',
    '132A': 'Pressac Communications',
    '132B': 'Origin Wireless',
    '132C': 'Connecte',
    '132D': 'Yokis',
    '132E': 'Xiamen Yankon',
    '132F': 'Yandex',
    '1330': 'Critical Software',
    '1331': 'Nortek Control',
    '1332': 'BrightAI',
    '1333': 'Becker Antriebe',
    '1334': 'Shenzhen TCL New Technology',
    '1335': 'Dexatek Technology',
    '1336': 'Elelabs International',
    '1337': 'Datek Wireless',
    '1338': 'Aldes',
    '1339': 'Savant',
    '133A': 'Ariston Thermo',
    '133B': 'Warema Renkhoff',
    '133C': 'VTech Holdings',
    '133D': 'Futurehome',
    '133E': 'Cognitive Systems',
    '133F': 'ASR Microelectronics',
    '1340': 'Airios',
    '1341': 'Guangdong Oppo Mobile',
    '1342': 'Beken',
    '1343': 'Corsair',
    '1344': 'Eltako',
    '1345': 'Chengdu Meross',
    '1346': 'Rafael Microelectronics',
    '1347': 'Aug. Winkhaus',
    '1348': 'Qingdao Haier',
    '1349': 'Apple',
    '134A': 'Rollease Acmeda',
    '134B': 'Nabu Casa',
    '134C': 'Simon Holding',
    '134D': 'KD Navien',
    '134E': 'tado',
    '134F': 'Mediola',
    '1350': 'PolynHome',
    '1351': 'Hoorii Technology',
    '1353': 'Kimin Electronics',
    '1354': 'Zyax',
    '1355': 'Baracoda',
    '1356': 'Lennox International',
    '1357': 'Teledatics',
    '1358': 'Top Victory Investments',
    '1359': 'GoQual',
    '135A': 'Siegenia-Aubi',
    '135B': 'Virtual Connected (Singapore)',
    '135C': 'Gigaset Communications',
    '135D': 'Nuki Home Solutions',
    '135E': 'DeviceBook',
    '135F': 'Consumer 2.0 (Rently)',
    '1360': 'Edison Labs (Orro)',
    '1361': 'Inovelli',
    '1362': 'Deveritec',
    '1363': 'Charter Communications',
    '1364': 'Monolithic Power Systems',
    '1365': 'Ningbo Dooya',
    '1366': 'Shenzhen SDMC',
    '1367': 'HP',
    '1368': 'Mui Lab',
    '1369': 'BHTronics',
    '136A': 'Akuvox (Xiamen)',
    '1490': 'Shelly',
    '152F': 'SberDevices',
    '1994': 'Gewiss',
    '1AD2': 'Livolo (custom)',
    '2794': 'Climax Technology',
    '6006': 'Google',
    '6666': 'Sprut.device (custom)',
    '7777': 'Lytko (custom)',
}


if __name__ == '__main__':
    main()
