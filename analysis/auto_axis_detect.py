"""
Sélection automatique de l'axe/signal le plus périodique par fenêtre d'exercice.

Motivation : le tilt combiné (3D) dilue parfois le vrai signal de répétitions
quand un mouvement plus ample (transition, descente d'une barre) domine le
calcul. Teste X, Y, Z bruts et le tilt combiné, choisit celui qui montre le
motif le plus régulier (variation des intervalles entre pics la plus faible),
pas juste le plus de variance.

Statut : exploratoire, validé sur 2 séances réelles seulement (19/09 et 20/09
2026). Nette amélioration observée pour squats/rowing/pompes ; tractions et
levées de jambes restent plus difficiles (voir mémoire du projet). PAS ENCORE
intégré dans l'app réelle (index.html) — celle-ci utilise encore l'algorithme
plus simple de core_offline_algorithm.py.
"""
import numpy as np
from scipy.ndimage import gaussian_filter1d
from scipy.signal import find_peaks

FS = 52.0

def compute_tilt(window):
    ax, ay, az = window[:,0], window[:,1], window[:,2]
    norm = np.sqrt(ax**2+ay**2+az**2); norm[norm<1e-6]=1e-6
    ux,uy,uz = ax/norm, ay/norm, az/norm
    n = len(window)
    searchN = min(n, int(FS*3))
    winN = max(5, int(FS*0.6))
    best_start, best_var = 0, np.inf
    for start in range(0, max(1, searchN-winN)):
        v = np.var(ux[start:start+winN])+np.var(uy[start:start+winN])+np.var(uz[start:start+winN])
        if v < best_var: best_var, best_start = v, start
    ref = np.array([ux[best_start:best_start+winN].mean(), uy[best_start:best_start+winN].mean(), uz[best_start:best_start+winN].mean()])
    ref /= np.linalg.norm(ref)
    cosang = np.clip(ux*ref[0]+uy*ref[1]+uz*ref[2], -1, 1)
    return np.degrees(np.arccos(cosang))

def periodicity_score(signal):
    """Lower inter-peak-interval coefficient of variation = more regular/periodic. Returns (score, n_peaks) — lower score is better; np.inf if not enough peaks to judge."""
    smooth = gaussian_filter1d(signal, sigma=0.15*FS)
    rng = smooth.max() - smooth.min()
    if rng < 1e-6: return np.inf, 0
    prominence = max(rng * 0.15, 1e-6)
    peaks, _ = find_peaks(smooth, prominence=prominence)
    if len(peaks) < 3: return np.inf, len(peaks)
    intervals = np.diff(peaks) / FS
    cv = intervals.std() / max(intervals.mean(), 1e-6)
    return cv, len(peaks)

def count_reps_on_signal(signal):
    smooth = gaussian_filter1d(signal, sigma=0.20*FS)
    graceN = min(int(FS*0.5), len(smooth)//4)
    pos = smooth[graceN:] if len(smooth) > graceN+10 else smooth
    if len(pos) < 10: return 0
    sorted_p = np.sort(pos)
    def pct(p):
        idx = (p/100)*(len(sorted_p)-1)
        lo,hi = int(np.floor(idx)), int(np.ceil(idx))
        if lo==hi: return sorted_p[lo]
        return sorted_p[lo]+(sorted_p[hi]-sorted_p[lo])*(idx-lo)
    lo, hi = pct(20), pct(80)
    mid, band = (lo+hi)/2, max(hi-lo, 1e-6)
    upper_th, lower_th = mid+0.15*band, mid-0.15*band
    state, last_counted_state, reps = None, None, 0
    for v in pos:
        new_state = 'top' if v>upper_th else ('bottom' if v<lower_th else state)
        if new_state != state and new_state is not None:
            if last_counted_state is not None and new_state==last_counted_state: reps+=1
            if last_counted_state is None: last_counted_state=new_state
            state = new_state
    return reps

def auto_axis_recount(window, verbose=False):
    """Try X, Y, Z, and combined tilt; pick whichever is most periodic; return (reps, winning_candidate, all_scores)."""
    candidates = {
        'X': window[:,0].astype(float),
        'Y': window[:,1].astype(float),
        'Z': window[:,2].astype(float),
        'tilt': compute_tilt(window),
    }
    scores = {}
    for name, sig in candidates.items():
        cv, n = periodicity_score(sig)
        scores[name] = (cv, n)
    # pick lowest CV among those with enough peaks
    valid = {k:v for k,v in scores.items() if v[0] != np.inf}
    if not valid:
        return count_reps_on_signal(candidates['tilt']), 'tilt (fallback)', scores
    winner = min(valid, key=lambda k: valid[k][0])
    reps = count_reps_on_signal(candidates[winner])
    return reps, winner, scores
