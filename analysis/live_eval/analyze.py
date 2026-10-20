import json,collections,statistics as st
rows=json.load(open('live_results.json'))
EX=['Rowing élastique','Pompes déclinées','Squats élastique','Pull ups']
def L(r,t): return r['live'].get(f'{t:.1f}')
print('n exercices:',len(rows), collections.Counter(r['ex'] for r in rows))
print('\n== 1. Compte direct à la fin vs compte final ==')
for t in (27.0,30.0,31.0):
    print(f' t={t}s')
    for ex in EX+['TOUS']:
        rs=[r for r in rows if ex=='TOUS' or r['ex']==ex]
        d=[(L(r,t) or 0)-r['final'] for r in rs]
        print(f'   {ex:18s} exact {100*sum(x==0 for x in d)/len(d):5.1f}%  ±1 {100*sum(abs(x)<=1 for x in d)/len(d):5.1f}%  moy {st.mean(d):+.2f}  pires {min(d)}/{max(d)}')
print('\n== 2. Suivi pendant l\'exercice (direct - reps réellement faites jusque-là), t=8..30 s ==')
for ex in EX+['TOUS']:
    d=[]
    for r in rows:
        if ex!='TOUS' and r['ex']!=ex: continue
        for k in range(16,61):
            t=k/2; v=L(r,t)
            if v is None: continue
            truth=sum(x<=t for x in r['times']); d.append(v-truth)
    c=collections.Counter(d)
    print(f'   {ex:18s} exact {100*c[0]/len(d):5.1f}%  -1 {100*c[-1]/len(d):5.1f}%  ≤-2 {100*sum(v for k,v in c.items() if k<=-2)/len(d):5.1f}%  +1 {100*c[1]/len(d):5.1f}%  ≥+2 {100*sum(v for k,v in c.items() if k>=2)/len(d):5.1f}%')
print('\n== 3. Annonce « record » (compte direct monotone atteint K) ==')
for ex in EX+['TOUS']:
    lat=[];early=0;miss=0;fa=0;nfa=0;n=0
    for r in rows:
        if ex!='TOUS' and r['ex']!=ex: continue
        ts=sorted(float(t) for t in r['live']); mx=0; first={}
        for t in ts:
            v=r['live'][f'{t:.1f}'] or 0; mx=max(mx,v)
            for K in range(1,mx+1): first.setdefault(K,t)
        F=r['final']
        for K in range(max(1,F-2),F+1):
            n+=1; tt=r['times'][K-1]
            if K not in first: miss+=1; continue
            dl=first[K]-tt; lat.append(dl)
            if dl<-0.25: early+=1
        nfa+=1
        if F+1 in first: fa+=1
    lat.sort()
    print(f'   {ex:18s} cibles {n}: ratées {100*miss/n:4.1f}%  trop tôt {100*early/n:4.1f}%  retard médian {st.median(lat):.1f}s (p90 {lat[int(.9*len(lat))]:.1f}s) | fausse annonce (K=final+1) {100*fa/nfa:4.1f}%')
