import sys, subprocess, json, collections, hashlib, pandas as pd, numpy as np
Z=sys.argv[1]; OUT=sys.argv[2]
D='202503_GTFS_DataSet/'
def rd(n,**k): return pd.read_csv(subprocess.Popen(['unzip','-p',Z,D+n],stdout=subprocess.PIPE).stdout,encoding='utf-8-sig',dtype=str,**k)
stops=rd('stops.txt'); routes=rd('routes.txt'); trips=rd('trips.txt'); cal=rd('calendar.txt'); tr=rd('transfers.txt')
rep={}
rep['files_present']=['agency.txt','calendar.txt','routes.txt','stop_times.txt','stops.txt','transfers.txt','trips.txt']
rep['files_absent']=['calendar_dates.txt','pathways.txt','shapes.txt','frequencies.txt','levels.txt','feed_info.txt']
rep['stops_fields']=list(stops.columns); rep['routes_fields']=list(routes.columns); rep['trips_fields']=list(trips.columns)
rep['calendar']=cal.to_dict('records')
stops['lat']=stops.stop_lat.astype(float); stops['lon']=stops.stop_lon.astype(float)
rep['stops_total']=len(stops); rep['stops_dup_id']=int(stops.stop_id.duplicated().sum())
rep['stops_bad_coord']=int(((stops.lat<33)|(stops.lat>39)|(stops.lon<124)|(stops.lon>132)).sum())
rep['routes_total']=len(routes); rep['routes_dup_id']=int(routes.route_id.duplicated().sum())
rep['trips_total']=len(trips); rep['trips_missing_route']=int((~trips.route_id.isin(routes.route_id)).sum())
rep['route_type_counts']=routes.route_type.value_counts().to_dict()
rep['transfers_rows']=len(tr); rep['transfers_missing_stop']=int((~tr.from_stop_id.isin(stops.stop_id)|~tr.to_stop_id.isin(stops.stop_id)).sum())
BB=(37.0,38.0,126.5,127.6)
inb=stops[(stops.lat>=BB[0])&(stops.lat<=BB[1])&(stops.lon>=BB[2])&(stops.lon<=BB[3])].reset_index(drop=True)
idx={s:i for i,s in enumerate(inb.stop_id)}
rep['bbox']=BB; rep['stops_in_bbox']=len(inb)
t2r=dict(zip(trips.trip_id,trips.route_id))
pat=collections.Counter(); patroute={}; cur=None; seq=[]; rows=0; missing_stop=0; prev=-1; unordered=0
first_dep={}; last_dep={}
def flush():
    if cur is None: return
    s=tuple(i for _,i in sorted(seq))
    if len(s)>=2:
        key=(t2r.get(cur,'?'),s); pat[key]+=1
p=subprocess.Popen(['unzip','-p',Z,D+'stop_times.txt'],stdout=subprocess.PIPE)
for ch in pd.read_csv(p.stdout,encoding='utf-8-sig',dtype={'trip_id':str,'stop_id':str,'stop_sequence':np.int32,'departure_time':str},usecols=['trip_id','stop_id','stop_sequence','departure_time'],chunksize=4_000_000):
    rows+=len(ch)
    for t,s,q in zip(ch.trip_id.values,ch.stop_id.values,ch.stop_sequence.values):
        if t!=cur:
            flush(); cur=t; seq=[]
        i=idx.get(s)
        if i is not None: seq.append((q,i))
flush()
rep['stop_times_rows']=rows; rep['patterns_in_bbox']=len(pat)
rmap=routes.set_index('route_id')
pats=[]
for (rid,s),n in pat.items():
    rt=rmap.route_type.get(rid,'?') if rid in rmap.index else '?'
    nm=rmap.route_short_name.get(rid,'') if rid in rmap.index else ''
    pats.append({'r':rid,'n':nm,'t':rt,'k':n,'s':list(s)})
used=sorted({i for p in pats for i in p['s']})
rep['stops_used_by_patterns']=len(used)
json.dump({'stops':[[r.stop_id,r.stop_name,round(r.lat,6),round(r.lon,6)] for r in inb.itertuples()],'patterns':pats},open(OUT,'w',encoding='utf-8'),ensure_ascii=False,separators=(',',':'))
json.dump(rep,open(OUT+'.report.json','w',encoding='utf-8'),ensure_ascii=False,indent=1,default=str)
print(json.dumps(rep,ensure_ascii=False,default=str)[:3000])
