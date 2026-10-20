import json,statistics as st
rows=json.load(open('live_results.json'))
EX=['Rowing élastique','Pompes déclinées','Squats élastique','Pull ups']
for hold in (1,2,3,4):
  print(f'-- confirmation : compte tenu pendant {hold*0.5:.1f} s')
  for ex in EX+['TOUS']:
    lat=[];early=miss=fa=n=nfa=0
    for r in rows:
        if ex!='TOUS' and r['ex']!=ex: continue
        ts=sorted(float(t) for t in r['live']); vals=[r['live'][f'{t:.1f}'] or 0 for t in ts]
        first={}
        for i in range(len(ts)):
            if i+1<hold: continue
            m=min(vals[i-hold+1:i+1])
            for K in range(1,m+1): first.setdefault(K,ts[i])
        F=r['final']
        for K in range(max(1,F-2),F+1):
            n+=1
            if K not in first: miss+=1; continue
            dl=first[K]-r['times'][K-1]; lat.append(dl); early+= dl<-0.25
        nfa+=1; fa+= (F+1) in first
    lat.sort()
    print(f'   {ex:18s} ratées {100*miss/n:4.1f}%  trop tôt {100*early/n:4.1f}%  retard médian {st.median(lat):.1f}s p90 {lat[int(.9*len(lat))]:.1f}s | fausses annonces {100*fa/nfa:4.1f}%')
