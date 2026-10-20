const fs=require('fs'); const {countGen,liveAt,countRepsWindow,REP_EXERCISES}=require('./evalLive.js');
const opt={edge:0.3, skip:0.5, minCore:4};
const files=fs.readdirSync('data').filter(f=>f.startsWith('seance')).sort();
const rows=[];
for(const f of files){
  const d=JSON.parse(fs.readFileSync('data/'+f)); const acc=d.acc.samples;
  for(const p of d.phaseLog){
    const ms=REP_EXERCISES[p.name]; if(ms==null) continue;
    const s0=p.tElapsedStart;
    const seg=acc.slice(Math.round((s0-2)*52),Math.round((s0+34)*52));
    if(seg.length<36*52-5) continue;
    const ref=countRepsWindow(seg,ms); const fin=countGen(seg,ms,9,21,28);
    if(!ref||!fin) continue;
    if(ref.reps!==fin.reps) console.error('MISMATCH',f,p.name,ref.reps,fin.reps);
    const live={};
    for(let t=4;t<=31.01;t+=0.5){ const r=liveAt(acc,s0,ms,+t.toFixed(1),opt); live[t.toFixed(1)]=r?r.reps:null; }
    rows.push({f:f.slice(7,17),ex:p.name,round:p.round,final:fin.reps,times:fin.times.map(x=>+x.toFixed(2)),live});
  }
  process.stderr.write(f+' done\n');
}
fs.writeFileSync('live_results.json',JSON.stringify(rows));
