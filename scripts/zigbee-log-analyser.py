#!/usr/bin/env python3
# (Short header omitted for brevity)
import argparse, json, re, sys, statistics, os
from collections import defaultdict, Counter
from datetime import datetime, timedelta
from typing import Dict, List, Tuple, Optional

# Lazy plotting
def _ensure_plotting_libs():
    global np, plt, matplotlib
    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        import numpy as np
        return plt, np
    except Exception as e:
        print("[WARNING] Matplotlib/Numpy not installed. Skipping heatmaps.", e, file=sys.stderr)
        return None, None

CLUSTER_NAMES = {
    '0000': 'Basic', '0001': 'Power Configuration', '0006': 'On/Off', '0019': 'OTA Upgrade',
    '0201': 'Thermostat', '0400': 'Illuminance Measurement', '0402': 'Temperature Measurement',
    '0403': 'Pressure Measurement', '0405': 'Relative Humidity', '0406': 'Occupancy Sensing',
    '0500': 'IAS Zone', '0702': 'Simple Metering', '0B04': 'Electrical Measurement',
    'FF01': 'Manufacturer-specific', 'FFF1': 'Manufacturer-specific',
}
ZDO_NAMES = {
    '0000': 'NWK Address Req', '8000': 'NWK Address Rsp',
    '0001': 'IEEE Address Req', '8001': 'IEEE Address Rsp',
    '0002': 'Node Descriptor Req', '8002': 'Node Descriptor Rsp',
    '0003': 'Power Descriptor Req', '8003': 'Power Descriptor Rsp',
    '0004': 'Simple Descriptor Req', '8004': 'Simple Descriptor Rsp',
    '0005': 'Active Endpoints Req', '8005': 'Active Endpoints Rsp',
    '0006': 'Match Descriptors Req', '8006': 'Match Descriptors Rsp',
    '0031': 'Mgmt LQI Req', '8031': 'Mgmt LQI Rsp',
    '0032': 'Mgmt Routing Req', '8032': 'Mgmt Routing Rsp',
}

GLOBAL_COMMANDS = {
    0x00: 'Read Attributes', 0x01: 'Read Attributes Response',
    0x02: 'Write Attributes', 0x04: 'Write Attributes Response',
    0x06: 'Configure Reporting', 0x07: 'Configure Reporting Response',
    0x08: 'Read Reporting Configuration', 0x09: 'Read Reporting Configuration Response',
    0x0A: 'Report Attributes', 0x0B: 'Default Response',
    0x0C: 'Discover Attributes', 0x0D: 'Discover Attributes Response',
    0x11: 'Discover Commands Received', 0x12: 'Discover Commands Received Response',
    0x13: 'Discover Commands Generated', 0x14: 'Discover Commands Generated Response',
    0x15: 'Discover Attributes Extended', 0x16: 'Discover Attributes Extended Response',
}

OTA_COMMANDS = {
    0x00: 'Image Notify',
    0x01: 'Query Next Image Request',
    0x02: 'Query Next Image Response',
    0x03: 'Image Block Request',
    0x04: 'Image Page Request',
    0x05: 'Image Block Response',
    0x06: 'Upgrade End Request',
    0x07: 'Upgrade End Response',
    0x08: 'Query Specific File',
}

# Normalization helper
def normalize_mfr_code(code) -> str:
    """Normalize to 4-hex uppercase (e.g., '119C'); accepts int, '0x119c', '119c', etc."""
    if code is None:
        return ''
    if isinstance(code, int):
        return f"{code & 0xFFFF:04X}"
    s = str(code).strip().upper().replace('0X', '')
    try:
        val = int(s, 16) & 0xFFFF
        return f"{val:04X}"
    except Exception:
        return s[:4]

# Minimal stub; will be merged with full list at bottom of file
KNOWN_MANUFACTURERS = {
    '119C': 'Sinopé Technologies',
    '10D0': 'Qorvo',
    '10F6': 'Invensys Controls',
    '104E': 'Centralite',
    '1049': 'Silicon Labs',
}

_text_re = re.compile(r"^name\s+(?P<name>.*?)\s+id\s+(?P<id>\d+)\s+profileId\s+(?P<profile>[0-9A-Fa-f]{4})\s+clusterId\s+(?P<cluster>[0-9A-Fa-f]{4})\s+sourceEndpoint\s+(?P<se>[0-9A-Fa-f]{2})\s+destinationEndpoint\s+(?P<de>[0-9A-Fa-f]{2}|FF)\s+groupId\s+(?P<group>[0-9A-Fa-f]{4})\s+sequence\s+(?P<seq>[0-9A-Fa-f]+)\s+lastHopLqi\s+(?P<lqi>\d+)\s+lastHopRssi\s+(?P<rssi>-?\d+)\s+time\s+(?P<time>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+type\s+(?P<type>\w+)\s+deviceId\s+(?P<device>\d+)\s+payload\s+(?P<payload>(?:[0-9A-Fa-f]{2}(?:\s+[0-9A-Fa-f]{2})*))$")

def parse_text_line(line:str)->Optional[dict]:
    """Parse a text-format Zigbee log line."""
    m=_text_re.match(line.strip())
    if not m: return None
    seqstr=m.group('seq')
    try: seq=int(seqstr,16)
    except ValueError: seq=int(seqstr)
    return {'name':m.group('name'),'network_id':int(m.group('id')),'device_id':int(m.group('device')),'profileId':m.group('profile').upper(),'clusterId':m.group('cluster').upper(),'sourceEndpoint':m.group('se'),'destinationEndpoint':m.group('de'),'groupId':m.group('group'),'sequence':seq,'lqi':int(m.group('lqi')),'rssi':int(m.group('rssi')),'time':m.group('time'),'type':m.group('type'),'payload':[p.upper() for p in m.group('payload').split()]}

def parse_json_line(line:str)->Optional[dict]:
    """Parse a JSON-format Zigbee log line."""
    try: d=json.loads(line)
    except json.JSONDecodeError: return None
    payload=d.get('payload', [])
    if isinstance(payload,list): pay=[x.upper() for x in payload]
    elif isinstance(payload,str): pay=[x.upper() for x in payload.split()]
    else: pay=[]
    return {'name':d.get('name') or 'unknown','network_id':d.get('id'),'device_id':d.get('deviceId'),'profileId':(d.get('profileId') or '').upper(),'clusterId':(d.get('clusterId') or '').upper(),'sourceEndpoint':(d.get('sourceEndpoint') or ''),'destinationEndpoint':(d.get('destinationEndpoint') or ''),'groupId':(d.get('groupId') or ''),'sequence':d.get('sequence'),'lqi':d.get('lastHopLqi'),'rssi':d.get('lastHopRssi'),'time':d.get('time'),'type':d.get('type'),'payload':pay}

def parse_zcl(payload_hex:List[str])->dict:
    """Parse ZCL (Zigbee Cluster Library) frame header from payload."""
    res={'fc':None,'frame_type':None,'manuf_specific':False,'direction':None,'disable_default_rsp':False,'manufacturer_code':None,'sequence':None,'command_id':None}
    if not payload_hex or len(payload_hex)<3: return res
    try: fc=int(payload_hex[0],16)
    except ValueError: return res
    res['fc']=fc
    res['frame_type']='cluster' if (fc & 0x03)==0x01 else 'global'
    res['manuf_specific']=bool(fc & 0x04)
    res['direction']='server_to_client' if (fc & 0x08) else 'client_to_server'
    res['disable_default_rsp']=bool(fc & 0x10)
    idx=1
    if res['manuf_specific']:
        if len(payload_hex)<idx+2: return res
        lo=int(payload_hex[idx],16); hi=int(payload_hex[idx+1],16)
        res['manufacturer_code']=f"{(hi<<8|lo):04X}"; idx+=2
    if len(payload_hex)<idx+2: return res
    res['sequence']=int(payload_hex[idx],16); res['command_id']=int(payload_hex[idx+1],16)
    return res

# OTA request parsing
_u16=lambda lo,hi: (int(hi,16)<<8)|int(lo,16)
_u32=lambda b0,b1,b2,b3: (int(b3,16)<<24)|(int(b2,16)<<16)|(int(b1,16)<<8)|int(b0,16)

def parse_ota_request_payload(payload_hex:List[str], zcl_header:dict)->Optional[dict]:
    """Parse OTA (Over-The-Air) upgrade request payload."""
    try:
        start=1+(2 if zcl_header.get('manuf_specific') else 0)+2
        data=payload_hex[start:]
        cmd=zcl_header.get('command_id')
        if cmd is None or not data: return None
        res={}
        if cmd==0x01:
            if len(data)<1+2+2+4: return None
            fc=int(data[0],16)
            res['field_control']=fc
            res['manufacturer_id']=_u16(data[1],data[2])
            res['image_type']=_u16(data[3],data[4])
            res['file_version']=_u32(data[5],data[6],data[7],data[8])
            return res
        if cmd==0x03:
            if len(data)<1+2+2+4+4+1: return None
            fc=int(data[0],16)
            res['field_control']=fc
            res['manufacturer_id']=_u16(data[1],data[2])
            res['image_type']=_u16(data[3],data[4])
            res['file_version']=_u32(data[5],data[6],data[7],data[8])
            res['file_offset']=_u32(data[9],data[10],data[11],data[12])
            res['max_data_size']=int(data[13],16)
            return res
        if cmd==0x06:
            if len(data)<1+2+2+4: return None
            res['status']=int(data[0],16)
            res['manufacturer_id']=_u16(data[1],data[2])
            res['image_type']=_u16(data[3],data[4])
            res['file_version']=_u32(data[5],data[6],data[7],data[8])
            return res
        return None
    except Exception:
        return None

# Manufacturer DB
def load_manufacturer_db(path:Optional[str])->Dict[str,str]:
    """Load manufacturer database from JSON file or use built-in defaults."""
    if path:
        try:
            with open(path,'r') as f: data=json.load(f)
            return {str(k).upper():v for k,v in data.items()}
        except (FileNotFoundError, json.JSONDecodeError, IOError) as e:
            print('[WARNING] Could not load manufacturer DB', e, file=sys.stderr)
    return KNOWN_MANUFACTURERS

# First pass with OTA-aware sniff
def build_dni_mfr_index(filepath:str)->Dict[int,str]:
    """Build an index mapping device network IDs to manufacturer codes."""
    index: Dict[int, str] = {}
    with open(filepath,'r') as f:
        for line in f:
            rec=parse_text_line(line) or parse_json_line(line)
            if not rec: continue
            pay=rec['payload']; z=parse_zcl(pay); dni=rec['network_id']
            if z['manufacturer_code'] and dni is not None:
                index[dni]=z['manufacturer_code']; continue
            if rec['clusterId']=='0019' and z['direction']=='client_to_server' and z['command_id'] is not None:
                meta=parse_ota_request_payload(pay, z)
                if meta and ('manufacturer_id' in meta) and dni is not None:
                    mfr_hex=f"{meta['manufacturer_id']:04X}"
                    if dni not in index or index[dni]!=mfr_hex:
                        index[dni]=mfr_hex
    return index

# Analysis
def analyze_log(filepath:str,mfr_filter:Optional[str],device_id_filter:Optional[int],device_name_filter:Optional[str],dni_filter:Optional[int],cluster_filters:Optional[List[str]],unsolicited_only:bool,output_file:Optional[str],index_cache:Optional[str],include_zdo:bool,show_global:bool,ota_details:bool):
    """Analyze Zigbee log file and extract device statistics."""
    if not os.path.exists(filepath):
        print(f"[ERROR] Log file not found: {filepath}", file=sys.stderr)
        sys.exit(1)

    dni_mfr_index={}
    if index_cache and os.path.exists(index_cache):
        try:
            with open(index_cache,'r') as f: dni_mfr_index={int(k):v for k,v in json.load(f).items()}
        except (json.JSONDecodeError, IOError, ValueError) as e:
            print('[WARNING] Could not read cache:', e, file=sys.stderr)
    else:
        dni_mfr_index=build_dni_mfr_index(filepath)
        if index_cache:
            try:
                with open(index_cache,'w') as f: json.dump(dni_mfr_index,f)
            except IOError as e:
                print('[WARNING] Could not write cache:', e, file=sys.stderr)

    devices=defaultdict(lambda:{'count':0,'lqis':[],'rssis':[],'mfr_code':None,'device_id':None,'network_id':None,'times':[],'per_cluster':Counter(),'per_cmd':Counter(),'per_global_cmd':Counter(),'per_zdo':Counter(),'per_ota_cmd':Counter(),'ota_req_mfr':None,'ota_req_image_type':None,'ota_req_current_version':None,'ota_last_offset':None,'ota_max_block_size':None,'ota_blocks_count':None,'ota_completed_download':None,'ota_phase':None})
    global_summary={'zdo':Counter(),'global_cmds':Counter(),'ota_cmds':Counter()}
    all_times=[]; out=open(output_file,'w') if output_file else None

    with open(filepath,'r') as f:
        for line in f:
            rec=parse_text_line(line) or parse_json_line(line)
            if not rec: continue
            if mfr_filter:
                ztmp=parse_zcl(rec['payload']); mfr_this=ztmp['manufacturer_code'] or dni_mfr_index.get(rec['network_id'])
                if not mfr_this or mfr_this.upper()!=mfr_filter.upper(): continue
            if device_id_filter and rec['device_id']!=device_id_filter: continue
            if device_name_filter and device_name_filter.lower() not in (rec['name'] or '').lower(): continue
            if dni_filter and rec['network_id']!=dni_filter: continue
            if cluster_filters and rec['clusterId'].upper() not in [c.upper() for c in cluster_filters]: continue

            is_zdo=(rec['profileId']=='0000')
            if is_zdo and not include_zdo: continue
            z=parse_zcl(rec['payload'])
            if unsolicited_only and z['direction']!='server_to_client' and rec['clusterId']!='0019':
                if not is_zdo: continue
            if out: out.write(line)

            name=rec['name'] or 'unknown'; dev=devices[name]
            dev['count']+=1
            if rec['lqi'] is not None: dev['lqis'].append(rec['lqi'])
            if rec['rssi'] is not None: dev['rssis'].append(rec['rssi'])
            mfr_code=z['manufacturer_code'] or dni_mfr_index.get(rec['network_id'])
            if mfr_code:
                dev['mfr_code']=mfr_code
            dev['device_id']=rec['device_id']; dev['network_id']=rec['network_id']
            dev['times'].append(rec['time']);
            if rec['time']: all_times.append(rec['time'])

            if is_zdo:
                label=ZDO_NAMES.get(rec['clusterId'], f"ZDO {rec['clusterId']}")
                dev['per_zdo'][label]+=1; global_summary['zdo'][label]+=1
            else:
                dev['per_cluster'][rec['clusterId']]+=1
                if z['frame_type']=='global' and show_global and z['command_id'] is not None:
                    gname=GLOBAL_COMMANDS.get(z['command_id'], f"Global 0x{z['command_id']:02X}")
                    dev['per_global_cmd'][gname]+=1; global_summary['global_cmds'][gname]+=1
                elif z['command_id'] is not None:
                    dev['per_cmd'][z['command_id']]+=1
                if ota_details and rec['clusterId']=='0019' and z['command_id'] is not None:
                    oname=OTA_COMMANDS.get(z['command_id'], f"OTA 0x{z['command_id']:02X}")
                    dev['per_ota_cmd'][oname]+=1; global_summary['ota_cmds'][oname]+=1
                    if z['direction']=='client_to_server':
                        meta=parse_ota_request_payload(rec['payload'], z)
                        if meta:
                            if z['command_id']==0x01:
                                dev['ota_req_mfr']=f"0x{meta.get('manufacturer_id'):04X}" if meta.get('manufacturer_id') is not None else None
                                dev['ota_req_image_type']=f"0x{meta.get('image_type'):04X}" if meta.get('image_type') is not None else None
                                fv=meta.get('file_version'); dev['ota_req_current_version']=f"0x{fv:08X}" if fv is not None else None
                                dev['ota_phase']='querying'
                            elif z['command_id']==0x03:
                                off=meta.get('file_offset')
                                if off is not None:
                                    prev=dev.get('ota_last_offset') or 0
                                    dev['ota_last_offset']=max(prev, off)
                                blk=meta.get('max_data_size')
                                if blk is not None: dev['ota_max_block_size']=blk
                                dev['ota_blocks_count']=(dev.get('ota_blocks_count') or 0)+1
                                dev['ota_phase']='downloading'
                            elif z['command_id']==0x06:
                                dev['ota_completed_download']=True; dev['ota_phase']='awaiting_upgrade'

    if out: out.close()
    time_range=(min(all_times), max(all_times)) if all_times else (None,None)
    return devices, time_range, dni_mfr_index, global_summary

def print_table(devices:dict, time_range:Tuple[Optional[str],Optional[str]], filters:dict, manufacturer_db:Dict[str,str], global_summary:dict):
    print('Zigbee Analysis — Unsolicited frames (and OTA)')
    print('='*118)
    if time_range[0] and time_range[1]:
        try:
            t1=datetime.strptime(time_range[0], '%Y-%m-%d %H:%M:%S.%f'); t2=datetime.strptime(time_range[1], '%Y-%m-%d %H:%M:%S.%f')
            dur=t2-t1; h,r=divmod(int(dur.total_seconds()),3600); m,s=divmod(r,60)
            print(f"Time range: {time_range[0]} → {time_range[1]} (duration {h}h {m}m)")
        except ValueError:
            print(f"Time range: {time_range[0]} → {time_range[1]}")
    parts=[]
    if filters.get('manufacturer'): parts.append(f"manufacturer={filters['manufacturer']}")
    if filters.get('dni'): parts.append(f"dni=0x{filters['dni']:04X}")
    if filters.get('device_id'): parts.append(f"device_id={filters['device_id']}")
    if filters.get('device_name'): parts.append(f"name contains '{filters['device_name']}'")
    if filters.get('clusters'): parts.append(f"clusters={','.join(filters['clusters'])}")
    if parts: print('Filters: '+', '.join(parts))
    print()

    print(f"{'Device Label':<34} {'Mfr':<6} {'Manufacturer':<28} {'Count':>6} {'Med LQI':>7} {'Med RSSI':>8}  Top clusters (count)")
    print('-'*118)
    total=0
    for name,info in sorted(devices.items(), key=lambda kv: kv[1]['count'], reverse=True):
        med_lqi=statistics.median(info['lqis']) if info['lqis'] else 0
        med_rssi=statistics.median(info['rssis']) if info['rssis'] else 0
        mfr_code=info['mfr_code'] or ''
        if not mfr_code and info.get('ota_req_mfr'):
            m=info['ota_req_mfr']; mfr_code=m.replace('0x','').upper() if isinstance(m,str) else mfr_code
        mfr_name=manufacturer_db.get(mfr_code,'')
        top=info['per_cluster'].most_common(3)
        top_str=', '.join([f"{CLUSTER_NAMES.get(c,c)}({n})" for c,n in top])
        print(f"{name:<34} {mfr_code:<6} {mfr_name:<28} {info['count']:>6} {med_lqi:>7.0f} {med_rssi:>8.0f}  {top_str}")
        total+=info['count']
    print('-'*118)
    print(f"{'TOTAL':<34} {'':<6} {'':<28} {total:>6}")

    if global_summary['global_cmds']:
        print('\nTop ZCL Global Commands:')
        for cmd,n in global_summary['global_cmds'].most_common(10): print(f"  {cmd:<36} {n:>6}")
    if global_summary['zdo']:
        print('\nTop ZDO Operations:')
        for zdo,n in global_summary['zdo'].most_common(10): print(f"  {zdo:<36} {n:>6}")
    if global_summary['ota_cmds']:
        print('\nTop OTA Commands:')
        for oc,n in global_summary['ota_cmds'].most_common(10): print(f"  {oc:<36} {n:>6}")
    phases=Counter()
    for _, info in devices.items():
        if info.get('ota_phase'): phases[info['ota_phase']]+=1
    if phases:
        print('\nOTA (inbound-only) phases:')
        for ph,n in phases.most_common(): print(f"  {ph:<20} {n:>6}")


def print_csv(devices:dict, manufacturer_db:Dict[str,str]):
    header=("Device Label,Mfr Code,Manufacturer,Count,Median LQI,Median RSSI,Network ID,Device ID,"
            "TopClusters,TopGlobalCmds,TopZDO,TopOTA,"
            "OTA Req Mfr,OTA Req ImageType,OTA Req CurrentVersion,OTA LastOffset,OTA MaxBlock,OTA Blocks,OTA Phase,OTA Completed")
    print(header)
    for name,info in sorted(devices.items(), key=lambda kv: kv[1]['count'], reverse=True):
        med_lqi=statistics.median(info['lqis']) if info['lqis'] else 0
        med_rssi=statistics.median(info['rssis']) if info['rssis'] else 0
        mfr_code=info['mfr_code'] or ''
        if not mfr_code and info.get('ota_req_mfr'):
            m=info['ota_req_mfr']; mfr_code=m.replace('0x','').upper() if isinstance(m,str) else mfr_code
        mfr_name=manufacturer_db.get(mfr_code,'')
        top_clusters=';'.join([f"{CLUSTER_NAMES.get(c,c)}({n})" for c,n in info['per_cluster'].most_common(3)])
        top_globals=';'.join([f"{k}({v})" for k,v in info['per_global_cmd'].most_common(3)])
        top_zdo=';'.join([f"{k}({v})" for k,v in info['per_zdo'].most_common(3)])
        top_ota=';'.join([f"{k}({v})" for k,v in info['per_ota_cmd'].most_common(3)])
        network_id_hex=f"0x{info['network_id']:04X}" if info['network_id'] else ''
        row=(f'"{name}",{mfr_code},{mfr_name},{info["count"]},{med_lqi:.0f},{med_rssi:.0f},'
             f'{network_id_hex},{info["device_id"]},'
             f'"{top_clusters}","{top_globals}","{top_zdo}","{top_ota}",'
             f'{info.get("ota_req_mfr","")},{info.get("ota_req_image_type","")},{info.get("ota_req_current_version","")},'
             f'{info.get("ota_last_offset","")},{info.get("ota_max_block_size","")},{info.get("ota_blocks_count","")},'
             f'{info.get("ota_phase","")},{info.get("ota_completed_download","")}')
        print(row)

# Heatmaps (forward-fill)
def build_time_bins(times:List[str], bin_seconds:int=60):
    """Build time bins for heatmap generation."""
    if bin_seconds <= 0:
        print('[ERROR] bin_seconds must be positive', file=sys.stderr)
        return [], {}
    if not times: return [], {}
    dt=[]
    for t in times:
        try: dt.append(datetime.strptime(t, '%Y-%m-%d %H:%M:%S.%f'))
        except ValueError: pass
    if not dt: return [], {}
    start=min(dt); end=max(dt)+timedelta(seconds=bin_seconds)
    bins=[]; cur=start.replace(microsecond=0)
    while cur<=end: bins.append(cur); cur+=timedelta(seconds=bin_seconds)
    index={}
    for t in times:
        try:
            tt=datetime.strptime(t, '%Y-%m-%d %H:%M:%S.%f')
            idx=int((tt-bins[0]).total_seconds()//bin_seconds)
            index[t]=idx
        except (ValueError, OverflowError): pass
    return bins, index

def generate_heatmaps(devices:dict, top_n:int=10, prefix:str='charts', bin_seconds:int=60):
    """Generate heatmap charts for top talkers and RSSI over time."""
    plt, np = _ensure_plotting_libs()
    if plt is None or np is None: return
    ranking=sorted(devices.items(), key=lambda kv: kv[1]['count'], reverse=True)[:top_n]
    if not ranking: print('[INFO] No devices to plot heatmaps.'); return
    all_times=[]
    for _,info in ranking: all_times.extend([t for t in info['times'] if t])
    bins, time_index = build_time_bins(all_times, bin_seconds)
    if not bins: print('[INFO] No temporal data for heatmaps.'); return
    dev_names=[name for name,_ in ranking]
    counts=np.zeros((len(dev_names), len(bins)), dtype=int)
    rssis=np.full((len(dev_names), len(bins)), np.nan, dtype=float)
    for i,(name,info) in enumerate(ranking):
        rssi_bins=defaultdict(list)
        rssi_list=info['rssis']; time_list=info['times']
        for t_idx,t in enumerate(time_list):
            if not t: continue
            idx=time_index.get(t)
            if idx is None or not (0<=idx<len(bins)): continue
            counts[i,idx]+=1
            if t_idx<len(rssi_list) and rssi_list[t_idx] is not None:
                rssi_bins[idx].append(rssi_list[t_idx])
        for idx,vals in rssi_bins.items():
            if vals: rssis[i,idx]=statistics.median(vals)
        last=None
        for idx in range(len(bins)):
            if not np.isnan(rssis[i,idx]): last=rssis[i,idx]
            elif last is not None: rssis[i,idx]=last
        first=None
        for idx in range(len(bins)):
            if not np.isnan(rssis[i,idx]): first=rssis[i,idx]; break
        if first is not None:
            for idx in range(len(bins)):
                if np.isnan(rssis[i,idx]): rssis[i,idx]=first
                else: break
    import os
    os.makedirs(os.path.dirname(prefix) or '.', exist_ok=True)
    xlabels=[b.strftime('%H:%M:%S') for b in bins]; step=max(1, len(bins)//10)
    plt.figure(figsize=(12, max(4, 0.4*len(dev_names))))
    plt.imshow(counts, aspect='auto', cmap='viridis'); plt.colorbar(label='Message count per bin')
    plt.yticks(range(len(dev_names)), dev_names); plt.xticks(range(0,len(bins),step), xlabels[::step], rotation=45)
    plt.title('Heatmap — Top talkers (counts per bin)'); plt.xlabel('Time'); plt.ylabel('Devices')
    out1=f"{prefix}_talkers.png"; plt.tight_layout(); plt.savefig(out1); plt.close(); print('[OK] Talkers heatmap:', out1)
    plt.figure(figsize=(12, max(4, 0.4*len(dev_names))))
    plt.imshow(rssis, aspect='auto', cmap='plasma', interpolation='nearest'); plt.colorbar(label='Median RSSI (dBm)')
    plt.yticks(range(len(dev_names)), dev_names); plt.xticks(range(0,len(bins),step), xlabels[::step], rotation=45)
    plt.title('Heatmap — RSSI noise (median per bin)'); plt.xlabel('Time'); plt.ylabel('Devices')
    out2=f"{prefix}_rssi.png"; plt.tight_layout(); plt.savefig(out2); plt.close(); print('[OK] RSSI heatmap:', out2)

# Main
def main():
    parser=argparse.ArgumentParser(description='Zigbee (Hubitat) Log Analyzer — ZDO, Global cmd, OTA inbound sniff, Heatmaps')
    parser.add_argument('logfile'); parser.add_argument('--manufacturer','-m'); parser.add_argument('--manufacturer-db')
    parser.add_argument('--device-id','-d',type=int); parser.add_argument('--device-name','-n'); parser.add_argument('--dni')
    parser.add_argument('--clusters', nargs='*'); parser.add_argument('--output','-o'); parser.add_argument('--csv', action='store_true')
    parser.add_argument('--unsolicited-only', action='store_true', default=False)
    parser.add_argument('--index-cache'); parser.add_argument('--heatmap', action='store_true'); parser.add_argument('--top-talkers', type=int, default=10)
    parser.add_argument('--heatmap-prefix', default='charts'); parser.add_argument('--include-zdo', action='store_true')
    parser.add_argument('--exclude-zdo', action='store_true'); parser.add_argument('--show-global', action='store_true'); parser.add_argument('--no-global', action='store_true')
    parser.add_argument('--ota-details', action='store_true')
    args=parser.parse_args()

    dni_filter=None
    if args.dni:
        dni_hex=args.dni.upper().replace('0X',''); dni_filter=int(dni_hex,16)
    # Include ZDO by default unless explicitly excluded
    include_zdo = not args.exclude_zdo
    # Show global commands by default unless explicitly disabled
    show_global = not args.no_global

    manufacturer_db=load_manufacturer_db(args.manufacturer_db)
    devices, time_range, dni_index, global_summary = analyze_log(
        args.logfile, mfr_filter=args.manufacturer, device_id_filter=args.device_id, device_name_filter=args.device_name,
        dni_filter=dni_filter, cluster_filters=args.clusters, unsolicited_only=args.unsolicited_only, output_file=args.output,
        index_cache=args.index_cache, include_zdo=include_zdo, show_global=show_global, ota_details=args.ota_details)

    if not devices:
        print('No frames matched the criteria.', file=sys.stderr); sys.exit(1)

    filters={'manufacturer':args.manufacturer,'dni':dni_filter,'device_id':args.device_id,'device_name':args.device_name,'clusters':args.clusters}
    if args.csv: print_csv(devices, manufacturer_db)
    else: print_table(devices, time_range, filters, manufacturer_db, global_summary)
    if args.heatmap: generate_heatmaps(devices, top_n=args.top_talkers, prefix=args.heatmap_prefix)

# =============================================================================
# Full Zigbee Alliance Manufacturer Codes Database
# =============================================================================
# Comprehensive list of manufacturer codes from the Zigbee Alliance
# Special codes: 'FFFF' = All/Match-All, '0000' = Unknown/Matter Standard
# =============================================================================
KNOWN_MANUFACTURERS_FULL = {
    '0000': 'Matter Standard', '0001': 'Panasonic', '0002': 'Sony', '0003': 'Samsung',
    '0004': 'Philips RF4CE', '0005': 'Freescale RF4CE', '0006': 'OKI Semiconductors RF4CE',
    '0007': 'Texas Instruments', '007B': 'Perenio (custom)', '1000': 'Cirronet', '1001': 'Chipcon',
    '1002': 'Ember', '1003': 'NTS', '1004': 'Freescale', '1005': 'IP-Com', '1006': 'San Juan Software',
    '1007': 'TUV', '1008': 'Integration', '1009': 'BM SpA', '100A': 'Awarepoint',
    '100B': 'Signify (Philips)', '100C': 'Luxoft', '100D': 'Korwin', '100E': 'One RF Technology',
    '100F': 'Software Technologies Group', '1010': 'Telegesis', '1011': 'Visonic', '1012': 'Insta',
    '1013': 'Atalum', '1014': 'Atmel', '1015': 'Develco', '1016': 'Honeywell', '1017': 'RadioPulse',
    '1018': 'Renesas', '1019': 'Xanadu Wireless', '101A': 'NEC Engineering', '101B': 'Yamatake',
    '101C': 'Tendril Networks', '101D': 'Assa Abloy', '101E': 'MaxStream', '101F': 'Neurocom',
    '1020': 'III (Taiwan)', '1021': 'Legrand', '1022': 'iControl', '1023': 'Raymarine',
    '1024': 'LS Research', '1025': 'Onity', '1026': 'Mono Products', '1027': 'RF Technologies',
    '1028': 'Itron', '1029': 'Tritech', '102A': 'Embedit', '102B': 'S3C', '102C': 'Siemens',
    '102D': 'Mindtech', '102E': 'LG Electronics', '102F': 'Mitsubishi Electric', '1030': 'Johnson Controls',
    '1031': 'Secure Meters UK', '1032': 'Knick', '1033': 'Viconics', '1034': 'Flexipanel',
    '1035': 'Piasim', '1036': 'Trane', '1037': 'NXP', '1038': 'Living Independently', '1039': 'AlertMe',
    '103A': 'Daintree', '103B': 'Aiji System', '103C': 'Telecom Italia', '103D': 'Mikrokrets',
    '103E': 'OKI Semiconductor', '103F': 'Newport Electronics', '1040': 'Control4',
    '1041': 'STMicroelectronics', '1042': 'Ad-Sol Nissin', '1043': 'DCSI', '1044': 'France Telecom',
    '1045': 'muNet', '1046': 'Autani', '1047': 'Colorado vNet', '1048': 'Aerocomm',
    '1049': 'Silicon Labs', '104A': 'Inncom', '104B': 'Cooper Power', '104C': 'Synapse',
    '104D': 'Fisher-Pierce', '104E': 'Centralite', '104F': 'Crane Wireless', '1050': 'Mobilarm',
    '1051': 'iMonitor', '1052': 'Bartech', '1053': 'Meshnetics', '1054': 'LS Industrial',
    '1055': 'Cason', '1056': 'Wireless Glue', '1057': 'Elster', '1058': 'SMS Tecnologia',
    '1059': 'Onset Computer', '105A': 'Riga Development', '105B': 'Energate', '105C': 'ConMed Linvatec',
    '105D': 'PowerMand', '105E': 'Schneider Electric', '105F': 'Eaton', '1060': 'Telular',
    '1061': 'Delphi Medical', '1062': 'EpiSensor', '1063': 'Landis+Gyr', '1064': 'Kaba Group',
    '1065': 'Shure', '1066': 'Comverge', '1067': 'DBS Lodging', '1068': 'Energy Aware',
    '1069': 'Hidalgo', '106A': 'Air2App', '106B': 'AMX', '106C': 'EDMI', '106D': 'Cyan',
    '106E': 'System SPA', '106F': 'Telit', '1070': 'Kaga Electronics', '1071': 'Astrel Group',
    '1072': 'Certicom', '1073': 'Gridpoint', '1074': 'Profile Systems', '1075': 'Compacta',
    '1076': 'Freestyle Technology', '1077': 'Alektrona', '1078': 'Computime', '1079': 'Remote Technologies',
    '107A': 'Wavecom', '107B': 'Energy Optimizers', '107C': 'GE', '107D': 'Jetlun',
    '107E': 'Cipher Systems', '107F': 'Corporate Systems Eng', '1080': 'Ecobee', '1081': 'SMK',
    '1082': 'Meshworks Wireless', '1083': 'Ellips', '1084': 'Secure Electrans', '1085': 'CEDO',
    '1086': 'Toshiba', '1087': 'Digi International', '1088': 'Ubilogix', '1089': 'Echelon',
    '1090': 'Green Energy Options', '1091': 'Silver Spring Networks', '1092': 'Black & Decker',
    '1093': 'Aztech Associates', '1094': 'A&D Co', '1095': 'Rainforest Automation',
    '1096': 'Carrier Electronics', '1097': 'SyChip/Murata', '1098': 'OpenPeak', '1099': 'PassiveSystems',
    '109A': 'MMB Research', '109B': 'Leviton', '109C': 'KEPCO', '109D': 'Comcast',
    '109E': 'NEC Electronics', '109F': 'Netvox', '10A0': 'U-Control', '10A1': 'Embedia',
    '10A2': 'Sensus', '10A3': 'SunRise Technologies', '10A4': 'Memtech', '10A5': 'Freebox',
    '10A6': 'M2 Labs', '10A7': 'British Gas', '10A8': 'Sentec', '10A9': 'Navetas',
    '10AA': 'Lightspeed Technologies', '10AB': 'OKI Electric', '10AC': 'S.I. Sistemas',
    '10AD': 'Dometic', '10AE': 'Alps', '10AF': 'EnergyHub', '10B0': 'Kamstrup', '10B1': 'EchoStar',
    '10B2': 'EnerNOC', '10B3': 'Eltav', '10B4': 'Belkin', '10B5': 'XstreamHD', '10B6': 'Saturn South',
    '10B7': 'GreenTrap', '10B8': 'SmartSynch', '10B9': 'Nyce Control', '10BA': 'ICM Controls',
    '10BB': 'Millennium Electronics', '10BC': 'Motorola', '10BD': 'Emerson White-Rodgers',
    '10BE': 'Radio Thermostat', '10BF': 'Omron', '10C0': 'GiiNii', '10C1': 'Fujitsu General',
    '10C2': 'Peel Technologies', '10C3': 'Accent', '10C4': 'ByteSnap', '10C5': 'NEC Tokin',
    '10C6': 'G4S Justice Services', '10C7': 'Trilliant Networks', '10C8': 'Electrolux Italia',
    '10C9': 'OnZo', '10CA': 'EnTek Systems', '10CB': 'Philips', '10CD': 'Indesit', '10CE': 'ThinkEco',
    '10CF': '2D2C', '10D0': 'Qorvo', '10D1': 'InterCel', '10D2': 'LG Electronics 2',
    '10D3': 'Mitsumi Electric', '10D4': 'Mitsumi Electric 2', '10D5': 'ZMDI', '10D6': 'Nest Labs',
    '10D7': 'Exegin Technologies', '10D8': 'Honeywell 2', '10D9': 'Takahata Precision',
    '10DA': 'Sumitomo Electric', '10DB': 'GE Energy', '10DC': 'GE Appliances', '10DD': 'Radiocrafts',
    '10DE': 'Ceiva', '10DF': 'TEC&CO', '10E0': 'Chameleon Technology', '10E1': 'Samsung 2',
    '10E2': 'Ruwido Austria', '10E3': 'Huawei', '10E4': 'Huawei 2', '10E5': 'Greenwave Reality',
    '10E6': 'BGlobal Metering', '10E7': 'Mindteck', '10E8': 'Ingersoll Rand', '10E9': 'Dius Computing',
    '10EA': 'Embedded Automation', '10EB': 'ABB', '10EC': 'Sony 2', '10ED': 'Genus Power',
    '10EE': 'Universal Electronics', '10EF': 'Universal Electronics 2', '10F0': 'Metrum Technologies',
    '10F1': 'Cisco', '10F2': 'Ubisys', '10F3': 'Consert', '10F4': 'Crestron', '10F5': 'Enphase Energy',
    '10F6': 'Invensys Controls', '10F7': 'Mueller Systems', '10F8': 'AAC Technologies', '10F9': 'U-NEXT',
    '10FA': 'Steelcase', '10FB': 'Telematics Wireless', '10FC': 'Samil Power', '10FD': 'Pace',
    '10FE': 'Osborne Coinage', '10FF': 'Powerwatch', '1100': 'Candeled', '1101': 'FlexGrid',
    '1102': 'Humax', '1103': 'Universal Devices', '1104': 'Advanced Energy', '1105': 'BEGA',
    '1106': 'Brunel University', '1107': 'Panasonic R&D Singapore', '1108': 'eSystems Research',
    '1109': 'Panamax', '110A': 'SmartThings', '110B': 'EM-Lite', '110C': 'OSRAM Sylvania',
    '110D': '2 Save Energy', '110E': 'Planet Innovation', '110F': 'Ambient Devices', '1110': 'Profalux',
    '1111': 'Billion Electric (BEC)', '1112': 'Embertec', '1113': 'IT Watchdogs', '1114': 'Reloc',
    '1115': 'Intel', '1116': 'Trend Electronics', '1117': 'Moxa', '1118': 'QEES',
    '1119': 'SAYME Wireless', '111A': 'Pentair Aquatic', '111B': 'Orbit Irrigation',
    '111C': 'California Eastern Labs', '111D': 'Comcast 2', '111E': 'IDT Technology', '111F': 'Pixela',
    '1120': 'TiVo', '1121': 'Fidure', '1122': 'Marvell Semiconductor', '1123': 'Wasion Group',
    '1124': 'Jasco Products', '1125': 'Shenzhen Kaifa', '1126': 'NetComm Wireless',
    '1127': 'Define Instruments', '1128': 'In Home Displays', '1129': 'Miele', '112A': 'Televes',
    '112B': 'Labelec', '112C': 'China Electronics Standard', '112D': 'Vectorform', '112E': 'Busch-Jaeger',
    '112F': 'Redpine Signals', '1130': 'Bridges Electronic', '1131': 'Sercomm',
    '1132': 'WSH (wirsindheller)', '1133': 'Bosch Security', '1134': 'EZEX', '1135': 'Dresden Elektronik',
    '1136': 'Meazon', '1137': 'Crow Electronic', '1138': 'Harvard Engineering', '1139': 'Andson (Beijing)',
    '113A': 'Adhoco', '113B': 'Waxman Consumer', '113C': 'Owon Technology', '113D': 'Hitron Technologies',
    '113E': 'Scemtec', '113F': 'Webee', '1140': 'Grid2Home', '1141': 'Telink Micro',
    '1142': 'Jasmine Systems', '1143': 'Bidgely', '1144': 'Lutron', '1145': 'IJENKO',
    '1146': 'Starfield Electronic', '1147': 'TCP', '1148': 'Rogers Communications', '1149': 'Cree',
    '114A': 'Robert Bosch LLC', '114B': 'Ibis Networks', '114C': 'Quirky', '114D': 'Efergy Technologies',
    '114E': 'Smartlabs', '114F': 'Everspring Industry', '1150': 'Swann Communications', '1151': 'Soneter',
    '1152': 'Samsung SDS', '1153': 'Uniband Electronic', '1154': 'Accton Technology',
    '1155': 'Bosch Thermotechnik', '1156': 'Wincor Nixdorf', '1157': 'Ohsung Electronics',
    '1158': 'Zen Within', '1159': 'Tech4Home', '115A': 'Nanoleaf', '115B': 'Keen Home',
    '115C': 'Poly-Control', '115D': 'Eastfield Lighting', '115E': 'IP Datatel',
    '115F': 'Lumi United (Aqara)', '1160': 'Sengled', '1161': 'Remote Solution', '1162': 'ABB Genway Xiamen',
    '1163': 'Zhejiang Rexense', '1164': 'ForEE Technology', '1165': 'Open Access Technology',
    '1166': 'Innr Lighting', '1167': 'Techworld Industries', '1168': 'Leedarson Lighting',
    '1169': 'Arzel Zoning', '116A': 'Holley Technology', '116B': 'Beldon Technologies',
    '116C': 'Flextronics', '116D': 'Shenzhen Meian', '116E': 'Lowes', '116F': 'Sigma Connectivity',
    '1171': 'Wulian', '1172': 'Plugwise', '1173': 'Titan Products', '1174': 'Ecospectral',
    '1175': 'D-Link', '1176': 'Technicolor Home USA', '1177': 'Opple Lighting', '1178': 'Wistron NeWeb',
    '1179': 'QMotion Shades', '117A': 'Insta GmbH', '117B': 'Shanghai Vancount', '117C': 'IKEA',
    '117D': 'RT-RK', '117E': 'Shenzhen Feibit', '117F': 'EuControls', '1180': 'Telkonet',
    '1181': 'Thermal Solution Resources', '1182': 'POMCube', '1183': 'Ei Electronics', '1184': 'Optoga',
    '1185': 'Stelpro', '1186': 'Lynxus Technologies', '1187': 'Semiconductor Components', '1188': 'TP-Link',
    '1189': 'LEDVANCE', '118A': 'Nortek', '118B': 'iRevo (Assa Abloy Korea)', '118C': 'Midea',
    '118D': 'ZF Friedrichshafen', '118E': 'Checkit', '118F': 'Aclara', '1190': 'Nokia',
    '1191': 'Goldcard High-Tech', '1192': 'George Wilson Industries', '1193': 'Easy Saver',
    '1194': 'ZTE', '1195': 'Arris', '1196': 'Reliance Big TV', '1197': 'Insight Energy (Powerley)',
    '1198': 'Thomas Research (Hubbell)', '1199': 'Li Seng Technology', '119A': 'System Level Solutions',
    '119B': 'Matrix Labs', '119C': 'Sinopé Technologies', '119D': 'Jiuzhou Greeble',
    '119E': 'Guangzhou Lanvee', '119F': 'Venstar', '1200': 'SLV', '1201': 'Halo Smart Labs',
    '1202': 'Scout Security', '1203': 'Alibaba China', '1204': 'Resolution Products', '1205': 'Smartlok',
    '1206': 'Lux Products', '1207': 'Vimar', '1208': 'Universal Lighting Tech', '1209': 'Robert Bosch GmbH',
    '120A': 'Accenture', '120B': 'Heiman Technology', '120C': 'Shenzhen Homa', '120D': 'Vision Electronics',
    '120E': 'Lenovo', '120F': 'Presciense R&D', '1210': 'Shenzhen Seastar', '1211': 'Sensative',
    '1212': 'SolarEdge', '1213': 'Zipato', '1214': 'China Fire & Security (iHorn)', '1215': 'Quby',
    '1216': 'Hangzhou Roombanker', '1217': 'Amazon Lab126', '1218': 'Paulmann Licht',
    '1219': 'Shenzhen Orvibo', '121A': 'TCI Telecommunications', '121B': 'Müller-Licht',
    '121C': 'Aurora Limited', '121D': 'SmartDCC', '121E': 'Shanghai Umeinfo', '121F': 'CarbonTrack',
    '1220': 'Somfy', '1221': 'Viessmann Elektronik', '1222': 'Hildebrand Technology',
    '1223': 'Onkyo Technology', '1224': 'Shenzhen Sunricher', '1225': 'Xiu Xiu Technology',
    '1226': 'Zumtobel Group', '1227': 'Shenzhen Kaadas', '1228': 'Shanghai Xiaoyan',
    '1229': 'Cypress Semiconductor', '122A': 'XAL GmbH', '122B': 'Inergy Systems',
    '122C': 'Alfred Kärcher', '122D': 'Adurolight', '122E': 'Groupe Muller', '122F': 'V-Mark Enterprises',
    '1230': 'Lead Energy', '1231': 'Ultimate IoT (Henan)', '1232': 'Axxess Industries',
    '1233': 'ThirdReality', '1234': 'DSR Corporation', '1235': 'Guangzhou Vensi',
    '1236': 'Schlage Lock (Allegion)', '1237': 'Net2Grid', '1238': 'Airam Electric',
    '1239': 'Immax WPB CZ', '123A': 'ZIV Automation', '123B': 'Hangzhou Imagic', '123C': 'Xiamen Leelen',
    '123D': 'Overkiz', '123E': 'Flonidan', '123F': 'HDL Automation', '1240': 'Ardomus Networks',
    '1241': 'Samjin', '1242': 'FireAngel Safety', '1243': 'Indra Sistemas', '1244': 'Shenzhen JBT',
    '1245': 'GE Lighting (Current)', '1246': 'Danfoss', '1247': 'Niviss PHP',
    '1248': 'Shenzhen Fengliyuan', '1249': 'Nexelec', '124A': 'Sichuan Behome', '124B': 'Fujian Star-net',
    '124C': 'Toshiba Visual Solutions', '124D': 'Latchable', '124E': 'L&S Deutschland',
    '124F': 'Gledopto', '1250': 'The Home Depot', '1251': 'Neonlite Distribution',
    '1252': 'Arlo Technologies', '1253': 'Xingluo Technology', '1254': 'Simon Electric China',
    '1255': 'Hangzhou Greatstar', '1256': 'Sequentric Energy', '1257': 'Solum',
    '1258': 'Eaglerise Electric', '1259': 'Fantem Technologies', '125A': 'Yunding Network (Beijing)',
    '125B': 'Atlantic Group', '125C': 'Xiamen Intretech', '125D': 'Tuya', '125E': 'DNAKE (Xiamen)',
    '125F': 'Niko', '1260': 'Emporia Energy', '1261': 'Sikom', '1262': 'Axis Labs',
    '1263': 'Current Products', '1264': 'Metersit', '1265': 'Hornbach Baumarkt', '1266': 'Diceworld',
    '1267': 'ARC Technology', '1268': 'Hangzhou Konke', '1269': 'Salto Systems', '126A': 'Shenzhen Shyugj',
    '126B': 'Brayden Automation', '126C': 'Environexus', '126D': 'Eltra', '126E': 'Xiaomi Communications',
    '126F': 'Shanghai Shuncom', '1270': 'Voltalis', '1271': 'Feelux', '1272': 'SmartPlus',
    '1273': 'Halemeier', '1274': 'Trust International', '1275': 'Duke Energy', '1276': 'Calix',
    '1277': 'Adeo', '1278': 'Connected Response', '1279': 'StroyEnergoKom', '127A': 'Lumitech Lighting',
    '127B': 'Verdant Environmental', '127C': 'Alfred International', '127D': 'Sansi LED',
    '127E': 'Mindtree', '127F': 'Nordic Semiconductor', '1280': 'Siterwell Electronics',
    '1281': 'Briloner Leuchten', '1282': 'Shenzhen SEI Technology', '1283': 'Copper Labs',
    '1284': 'Delta Dore', '1285': 'Hager Group', '1286': 'Shenzhen Coolkit (Sonoff)',
    '1287': 'Hangzhou Sky-Lighting', '1288': 'E.ON', '1289': 'Lidl', '128A': 'Sichuan Changhong',
    '128B': 'NodOn', '128C': 'Jiangxi Innotech', '128D': 'Mercator', '128E': 'Beijing Ruying',
    '128F': 'Eglo Leuchten', '1290': 'Pietro Fiorentini', '1291': 'Zehnder Group', '1292': 'BRK Brands',
    '1293': 'Askey Computer', '1294': 'PassiveBolt', '1295': 'AVM (Fritz)', '1296': 'Ningbo Suntech',
    '1297': 'Stello', '1298': 'Vivint Smart Home', '1299': 'Namron', '129A': 'Rademacher',
    '129B': 'OMO Systems', '129C': 'Siglis', '129D': 'Imhotep Creation', '129E': 'iCasa',
    '129F': 'Level Home', '1300': 'TIS Control', '1301': 'Radisys India', '1302': 'Veea',
    '1303': 'Fell Technology', '1304': 'Sowilo Design', '1305': 'Lexi Devices', '1306': 'LIFX',
    '1307': 'Grundfos', '1308': 'Sourcing & Creation', '1309': 'Kraken Technologies', '130A': 'Eve Systems',
    '130B': 'Lite-On Technology', '130C': 'Focalcrest', '130D': 'Bouffalo Lab', '130E': 'Wyze Labs',
    '130F': 'Z-Wave Europe', '1310': 'Aeotec', '1311': 'NGSTB', '1312': 'Qingdao Yeelink (Yeelight)',
    '1313': 'E-Smart Home', '1314': 'Fibaro', '1315': 'Prolitech', '1316': 'Pankore IC',
    '1317': 'Logitech', '1318': 'Piaro', '1319': 'Mitsubishi Electric US', '131A': 'Resideo Technologies',
    '131B': 'Espressif Systems', '131C': 'Hella', '131D': 'Geberit International', '131E': 'Came SpA',
    '131F': 'Guangzhou Elite Education', '1320': 'PhyPlus Microelectronics',
    '1321': 'Shenzhen Sonoff (ITEAD)', '1322': 'Safe4 Security', '1323': 'Shanghai MXCHIP',
    '1324': 'HDC i-Controls', '1325': 'Zuma Array', '1326': 'Decelect', '1327': 'Mill International',
    '1328': 'HomeWizard', '1329': 'Shenzhen Topband', '132A': 'Pressac Communications',
    '132B': 'Origin Wireless', '132C': 'Connecte', '132D': 'Yokis', '132E': 'Xiamen Yankon',
    '132F': 'Yandex', '1330': 'Critical Software', '1331': 'Nortek Control', '1332': 'BrightAI',
    '1333': 'Becker Antriebe', '1334': 'Shenzhen TCL New Technology', '1335': 'Dexatek Technology',
    '1336': 'Elelabs International', '1337': 'Datek Wireless', '1338': 'Aldes', '1339': 'Savant',
    '133A': 'Ariston Thermo', '133B': 'Warema Renkhoff', '133C': 'VTech Holdings', '133D': 'Futurehome',
    '133E': 'Cognitive Systems', '133F': 'ASR Microelectronics', '1340': 'Airios',
    '1341': 'Guangdong Oppo Mobile', '1342': 'Beken', '1343': 'Corsair', '1344': 'Eltako',
    '1345': 'Chengdu Meross', '1346': 'Rafael Microelectronics', '1347': 'Aug. Winkhaus',
    '1348': 'Qingdao Haier', '1349': 'Apple', '134A': 'Rollease Acmeda', '134B': 'Nabu Casa',
    '134C': 'Simon Holding', '134D': 'KD Navien', '134E': 'tado', '134F': 'Mediola',
    '1350': 'PolynHome', '1351': 'Hoorii Technology', '1353': 'Kimin Electronics', '1354': 'Zyax',
    '1355': 'Baracoda', '1356': 'Lennox International', '1357': 'Teledatics',
    '1358': 'Top Victory Investments', '1359': 'GoQual', '135A': 'Siegenia-Aubi',
    '135B': 'Virtual Connected (Singapore)', '135C': 'Gigaset Communications', '135D': 'Nuki Home Solutions',
    '135E': 'DeviceBook', '135F': 'Consumer 2.0 (Rently)', '1360': 'Edison Labs (Orro)',
    '1361': 'Inovelli', '1362': 'Deveritec', '1363': 'Charter Communications',
    '1364': 'Monolithic Power Systems', '1365': 'Ningbo Dooya', '1366': 'Shenzhen SDMC', '1367': 'HP',
    '1368': 'Mui Lab', '1369': 'BHTronics', '136A': 'Akuvox (Xiamen)', '1490': 'Shelly',
    '152F': 'SberDevices', '1994': 'Gewiss', '1AD2': 'Livolo (custom)', '2794': 'Climax Technology',
    '6006': 'Google', '6666': 'Sprut.device (custom)', '7777': 'Lytko (custom)',
}

# Merge full manufacturer database into main dictionary
try:
    for k, v in list(KNOWN_MANUFACTURERS_FULL.items()):
        key = normalize_mfr_code(k)
        KNOWN_MANUFACTURERS_FULL.pop(k)
        KNOWN_MANUFACTURERS_FULL[key] = v
    KNOWN_MANUFACTURERS.update(KNOWN_MANUFACTURERS_FULL)
except Exception as e:
    print('[WARNING] Could not merge full manufacturer table:', e, file=sys.stderr)

if __name__=='__main__': main()
