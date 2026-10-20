"""Comptage des reps HIIT v3 (20/10/2026) — référence Python de analyzeSessionReps() dans index.html.

Fenêtre [-2 s, +34 s] autour du top de chaque exercice. Candidats X, Y, Z, tilt et axe principal
(ACP, indépendant de l'orientation du capteur), lissés 0,15 s et sans dérive lente (1,5 s).
Le candidat le plus périodique au cœur (9–21 s) donne la période P ; on détecte les pics, on tire
un gabarit de rep du cœur, puis on étend depuis le centre tant que les pics ressemblent au gabarit
(forme ≥ 0,6, amplitude 0,45–1,5×) avec un écart ≤ 1,7 P (un pic raté comblé si la série continue).

Validation : comptage visuel des 32 fenêtres des séances du 16 et du 20/10 → écart moyen +0,3,
erreur absolue moyenne 0,8 rep, max 2,5 (v1 : 2,2 / max 7,5 ; v2-detrend : 1,5 / max 4,5).
Remplace auto_axis_detect.py + bayesian_boundary.py dans l'app.

Usage : python analysis/rep_template_chain.py clair1.json clair2.json …  (séances déchiffrées)
"""
import json,sys,numpy as np
from scipy.ndimage import gaussian_filter1d as gf
from scipy.signal import find_peaks
FS=52.0
EX=['Rowing élastique','Pompes déclinées','Squats élastique','Pull ups']
def tilt(seg):
    u=seg/np.linalg.norm(seg,axis=1,keepdims=True); W=int(.6*FS); best=None
    for s in range(0,max(1,int(3*FS)-W)):
        v=u[s:s+W].var(0).sum()
        if best is None or v<best[0]: best=(v,s)
    r=u[best[1]:best[1]+W].mean(0); r/=np.linalg.norm(r)
    return np.degrees(np.arccos(np.clip(u@r,-1,1)))
def prep(x): return gf(x,.15*FS)-gf(x,1.5*FS)
def cands(seg,core):
    c={'X':seg[:,0],'Y':seg[:,1],'Z':seg[:,2],'tilt':tilt(seg)}
    d=prep_axes=np.stack([prep(seg[:,k]) for k in range(3)],1)
    cc=d[core[0]:core[1]]; w,v=np.linalg.eigh(np.cov(cc.T)); c2={k:prep(x.astype(float)) for k,x in c.items()}
    c2['pca']=d@v[:,-1]; return c2
def acper(x,lo=.8,hi=2.2):
    x=x-x.mean(); v0=(x*x).sum()+1e-9; best=(-1,1)
    for L in range(int(lo*FS),int(hi*FS)):
        r=(x[:-L]*x[L:]).sum()/v0*len(x)/(len(x)-L)
        if r>best[0]: best=(r,L/FS)
    return best
MINS={'Rowing élastique':0.8,'Pompes déclinées':2.0,'Squats élastique':4.0,'Pull ups':3.5}
def count_win(seg,off=2.0,dbg=False,prom_f=0.25,shape_thr=0.6,hmin=0.45,hmax=1.5,smin=0.0,emax=28.0):
    core=(int((off+9)*FS),int((off+21)*FS))
    C=cands(seg,core)
    sc={k:acper(x[core[0]:core[1]]) for k,x in C.items()}
    k=max(sc,key=lambda k:sc[k][0]); x=C[k]; r,P=sc[k]
    c=x[core[0]:core[1]]
    if np.mean((c-c.mean())**3)<0: x=-x; c=-c
    pk,pr=find_peaks(x,distance=max(1,int(.5*P*FS)),prominence=np.ptp(c)*prom_f)
    S=int((off+smin)*FS); E=int((off+emax)*FS); pk=pk[(pk>=S)&(pk<=E)]
    if len(pk)<3: return 0,k,P
    H=int(.5*P*FS)
    def win(i):
        w=np.take(x,np.clip(np.arange(i-H,i+H+1),0,len(x)-1)); return w-w.mean()
    cin=[i for i,p in enumerate(pk) if core[0]<=p<core[1]]
    if len(cin)<3: return len(pk),k,P
    T=np.median([win(pk[i]) for i in cin],0)
    hmed=np.median([np.ptp(win(pk[i])) for i in cin])
    def ok(i):
        w=win(pk[i]); cr=w@T/(np.linalg.norm(w)*np.linalg.norm(T)+1e-9); h=np.ptp(w)
        return cr>=shape_thr and hmin*hmed<=h<=hmax*hmed
    oks=[ok(i) for i in range(len(pk))]
    centre=(core[0]+core[1])//2; j=int(np.argmin(np.abs(pk-centre)))
    def step(i,d):
        # normal step from peak i in direction d: returns (new_i, reps) or None
        k=i+d
        if not (0<=k<len(pk)): return None
        gap=abs(pk[k]-pk[i])/(P*FS)
        if oks[k] and gap<=1.7: return (k,1)
        k2=i+2*d
        if 0<=k2<len(pk) and oks[k2] and abs(pk[k2]-pk[i])/(P*FS)<=2.7: return (k2,2)
        return None
    def bridge(i,d):
        k=i+d
        if not (0<=k<len(pk)) or not oks[k]: return None
        gap=abs(pk[k]-pk[i])/(P*FS)
        if not (1.7<gap<=2.7): return None
        a1=step(k,d)
        if a1 is None or a1[1]!=1: return None
        a2=step(a1[0],d)
        if a2 is None or a2[1]!=1: return None
        return (k,2)
    n=1; ends=[]
    for d in (-1,1):
        i=j
        while True:
            r=step(i,d) or bridge(i,d)
            if r is None: break
            i,c=r; n+=c
        ends.append(i)
    lo,hi=ends
    if dbg: return n,k,P,pk[lo]/FS-off,pk[hi]/FS-off
    return n,k,P
def analyze(d,**kw):
    a=np.array(d['acc']['samples'],float); out={}
    for p in d['phaseLog']:
        if p['name'] not in EX: continue
        s0=p['tElapsedStart']; seg=a[max(0,int((s0-2)*FS)):int((s0+34)*FS)]
        out[(p['name'],p['round'])]=count_win(seg,dbg=True,smin=MINS[p['name']],**kw)
    return out
if __name__=='__main__':
    for f in sys.argv[1:]:
        r=analyze(json.load(open(f)))
        print(f)
        for e in EX: print('  ',e,[r[(e,i)][0] for i in range(1,5) if (e,i) in r])
