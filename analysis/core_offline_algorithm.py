"""
Algorithme "précis" (offline) de comptage de répétitions — accéléromètre seul.

Ceci reproduit exactement ce que fait offlineReanalyze() dans polar-live.html /
decodeRecFile() + le state-machine à seuils dans index.html (JS). Version Python
pour la recherche et l'analyse hors-ligne des séances réelles.

Validé à 11/14 exercices à ±1 rep sur le jeu de données de référence (14 configs,
polar-datacollect.html). Voir /projects/.../areas/polar-rep-counting.md pour le
contexte complet.
"""
import numpy as np
from scipy.ndimage import gaussian_filter1d

FS = 52.0

def compute_tilt(acc_samples_xyz, search_s=3.0, win_s=0.6, smooth_sigma_s=0.20):
    """acc_samples_xyz: numpy array (N,3) de valeurs ACC brutes (mG). Retourne le
    signal de tilt lissé (degrés), calculé par rapport à une référence = la fenêtre
    la plus stable trouvée dans les search_s premières secondes."""
    ax, ay, az = acc_samples_xyz[:,0], acc_samples_xyz[:,1], acc_samples_xyz[:,2]
    norm = np.sqrt(ax**2 + ay**2 + az**2); norm[norm < 1e-6] = 1e-6
    ux, uy, uz = ax/norm, ay/norm, az/norm

    n = len(acc_samples_xyz)
    search_n = min(n, int(FS*search_s))
    win_n = max(5, int(FS*win_s))
    best_start, best_var = 0, np.inf
    for start in range(0, max(1, search_n - win_n)):
        v = np.var(ux[start:start+win_n]) + np.var(uy[start:start+win_n]) + np.var(uz[start:start+win_n])
        if v < best_var:
            best_var, best_start = v, start
    ref = np.array([ux[best_start:best_start+win_n].mean(), uy[best_start:best_start+win_n].mean(), uz[best_start:best_start+win_n].mean()])
    ref /= np.linalg.norm(ref)

    cosang = np.clip(ux*ref[0] + uy*ref[1] + uz*ref[2], -1, 1)
    tilt = np.degrees(np.arccos(cosang))
    return gaussian_filter1d(tilt, sigma=smooth_sigma_s*FS)

def count_reps(signal_1d, grace_s=0.5, low_pct=20, high_pct=80, band_frac=0.15):
    """State-machine à hystérésis sur un signal 1D quelconque (tilt, ou un axe brut
    choisi par auto_axis_detect.py). Compte un rep à chaque aller-retour complet
    entre les bandes basse/haute, définies par percentile adaptatif."""
    grace_n = min(int(FS*grace_s), len(signal_1d)//4)
    pos = signal_1d[grace_n:] if len(signal_1d) > grace_n + 10 else signal_1d
    if len(pos) < 10:
        return 0
    sorted_p = np.sort(pos)
    def pct(p):
        idx = (p/100) * (len(sorted_p)-1)
        lo, hi = int(np.floor(idx)), int(np.ceil(idx))
        if lo == hi: return sorted_p[lo]
        return sorted_p[lo] + (sorted_p[hi]-sorted_p[lo]) * (idx-lo)
    lo, hi = pct(low_pct), pct(high_pct)
    mid, band = (lo+hi)/2, max(hi-lo, 1e-6)
    upper_th, lower_th = mid + band_frac*band, mid - band_frac*band

    state, last_counted_state, reps = None, None, 0
    for v in pos:
        new_state = 'top' if v > upper_th else ('bottom' if v < lower_th else state)
        if new_state != state and new_state is not None:
            if last_counted_state is not None and new_state == last_counted_state:
                reps += 1
            if last_counted_state is None:
                last_counted_state = new_state
            state = new_state
    return reps
