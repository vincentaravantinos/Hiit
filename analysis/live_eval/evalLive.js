const fs=require('fs');
const src=fs.readFileSync('all.js','utf8');
function grab(n){const i=src.indexOf('function '+n+'(');if(i<0)throw n;let d=0;for(let k=src.indexOf('{',i);;k++){if(src[k]=='{')d++;else if(src[k]=='}'&&!--d)return src.slice(i,k+1);}}
const FS_ALGO=52.0, REP_WIN_BEFORE_S=2, REP_WIN_AFTER_S=34, REP_END_MAX_S=28, REP_CORE=[9,21];
const REP_EXERCISES={'Rowing élastique':0.8,'Pompes déclinées':2.0,'Squats élastique':4.0,'Pull ups':3.5};
eval(['gaussFilterReflect','repPrep','repTiltRaw','repCandidates','repAutocorrPeriod','repFindPeaks','repMedian','countRepsWindow'].map(grab).join('\n'));

// Version généralisée : cœur et fin paramétrables, renvoie aussi la position des reps.
function countGen(seg, minStartS, coreS, coreE, endS){
  const off=REP_WIN_BEFORE_S, FS=FS_ALGO;
  const c0=Math.floor((off+coreS)*FS), c1=Math.floor((off+coreE)*FS);
  if (c1>seg.length) return null;
  const C=repCandidates(seg,c0,c1);
  let k=null,best=null;
  for(const name in C){const a=repAutocorrPeriod(C[name].slice(c0,c1)); if(!best||a.r>best.r){best=a;k=name;}}
  const P=best.P; let x=C[k];
  const core=x.slice(c0,c1), cm=core.reduce((a,b)=>a+b,0)/core.length;
  if(core.reduce((a,v)=>a+(v-cm)**3,0)<0) x=x.map(v=>-v);
  const cx=x.slice(c0,c1), ptp=Math.max(...cx)-Math.min(...cx);
  const S=Math.floor((off+minStartS)*FS), E=Math.min(Math.floor((off+endS)*FS), seg.length-1);
  const pk=repFindPeaks(x,Math.max(1,Math.floor(0.5*P*FS)),ptp*0.25).filter(p=>p>=S&&p<=E);
  const toS=p=>p/FS-off;
  if(pk.length<3) return {reps:pk.length,times:pk.map(toS)};
  const H=Math.floor(0.5*P*FS);
  const win=p=>{const w=[];for(let i=p-H;i<=p+H;i++)w.push(x[Math.min(x.length-1,Math.max(0,i))]);const m=w.reduce((a,b)=>a+b,0)/w.length;return w.map(v=>v-m);};
  const cin=pk.filter(p=>p>=c0&&p<c1);
  if(cin.length<3) return {reps:pk.length,times:pk.map(toS)};
  const cw=cin.map(win), T=cw[0].map((_,i)=>repMedian(cw.map(w=>w[i])));
  const ptpW=w=>Math.max(...w)-Math.min(...w), hmed=repMedian(cw.map(ptpW)), nT=Math.hypot(...T)+1e-9;
  const oks=pk.map(p=>{const w=win(p),h=ptpW(w);const cr=w.reduce((a,v,i)=>a+v*T[i],0)/(Math.hypot(...w)*nT+1e-9);return cr>=0.6&&h>=0.45*hmed&&h<=1.5*hmed;});
  const centre=(c0+c1)>>1; let j=0; pk.forEach((p,i)=>{if(Math.abs(p-centre)<Math.abs(pk[j]-centre))j=i;});
  const gapP=(a,b)=>Math.abs(pk[b]-pk[a])/(P*FS);
  const step=(i,d)=>{const k1=i+d;if(k1<0||k1>=pk.length)return null;if(oks[k1]&&gapP(i,k1)<=1.7)return[k1,1];const k2=i+2*d;if(k2>=0&&k2<pk.length&&oks[k2]&&gapP(i,k2)<=2.7)return[k2,2];return null;};
  const bridge=(i,d)=>{const k1=i+d;if(k1<0||k1>=pk.length||!oks[k1])return null;const g=gapP(i,k1);if(!(g>1.7&&g<=2.7))return null;const a1=step(k1,d);if(!a1||a1[1]!==1)return null;const a2=step(a1[0],d);if(!a2||a2[1]!==1)return null;return[k1,2];};
  const times=[toS(pk[j])];
  for(const d of[-1,1]){let i=j;for(;;){const r=step(i,d)||bridge(i,d);if(!r)break;if(r[1]===2)times.push(toS((pk[i]+pk[r[0]])/2));i=r[0];times.push(toS(pk[i]));}}
  times.sort((a,b)=>a-b);
  return {reps:times.length,times};
}
// Compte en direct à l'instant t (s depuis le top de l'exercice), données ≤ t seulement.
function liveAt(acc, s0, minStart, t, opt){
  const a=Math.round((s0-REP_WIN_BEFORE_S)*FS_ALGO), b=Math.round((s0+t)*FS_ALGO);
  const seg=acc.slice(a,b);
  let ce, cs;
  if (t-opt.edge >= REP_CORE[1]) { cs=REP_CORE[0]; ce=REP_CORE[1]; }
  else { ce=t-opt.edge; cs=Math.max(minStart+opt.skip, ce-12); }
  if (ce-cs < opt.minCore) return null;
  return countGen(seg, minStart, cs, ce, Math.min(REP_END_MAX_S, t));
}
module.exports={countGen,liveAt,countRepsWindow,REP_EXERCISES};
