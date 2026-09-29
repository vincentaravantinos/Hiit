"""
Segmentation automatique nage/pause dans un enregistrement continu.

Fait glisser un score de régularité (variation des intervalles entre pics)
sur toute la durée, classe chaque fenêtre "nage" (mouvement périodique) ou
"pause" (irrégulier ou plat), puis fusionne en segments contigus.

Fenêtre volontairement plus longue (14s) que pour les exercices au sol (8s),
car la cadence de nage est plus lente (~3-4s/mouvement) et a besoin de plus
de temps pour accumuler assez de pics à juger.

Statut : validé sur 1 séance réelle (29/09), qui avait un long repos après la
nage — le seuil (0.6) sépare cleanly les deux régimes sur ce cas. À confirmer
sur davantage de séances avant de le considérer robuste.
"""
import numpy as np
from scipy.ndimage import gaussian_filter1d
from scipy.signal import find_peaks

FS = 52.0

def tilt_signal(acc, smooth_sigma_s=0.15):
    ax,ay,az = acc[:,0],acc[:,1],acc[:,2]
    norm = np.sqrt(ax**2+ay**2+az**2); norm[norm<1e-6]=1e-6
    ux,uy,uz = ax/norm, ay/norm, az/norm
    n = len(acc)
    winN = max(5, min(int(FS*0.6), n//2))
    searchN = min(n, int(FS*3))
    best_start, best_var = 0, np.inf
    for start in range(0, max(1,searchN-winN)):
        v = np.var(ux[start:start+winN])+np.var(uy[start:start+winN])+np.var(uz[start:start+winN])
        if v < best_var: best_var, best_start = v, start
    ref = np.array([ux[best_start:best_start+winN].mean(), uy[best_start:best_start+winN].mean(), uz[best_start:best_start+winN].mean()])
    ref /= np.linalg.norm(ref)
    cosang = np.clip(ux*ref[0]+uy*ref[1]+uz*ref[2], -1, 1)
    return gaussian_filter1d(np.degrees(np.arccos(cosang)), sigma=smooth_sigma_s*FS)

def sliding_regularity(tilt, win_s=14.0, step_s=2.0):
    win_n = int(win_s*FS)
    step_n = int(step_s*FS)
    scores, centers = [], []
    for start in range(0, max(1, len(tilt)-win_n), step_n):
        seg = tilt[start:start+win_n]
        rng = seg.max()-seg.min()
        if rng < 8:
            scores.append(2.0)
        else:
            peaks,_ = find_peaks(seg, prominence=max(rng*0.15,1))
            if len(peaks) < 3:
                scores.append(1.5)
            else:
                intervals = np.diff(peaks)/FS
                scores.append(intervals.std()/max(intervals.mean(),1e-6))
        centers.append((start+win_n/2)/FS)
    return np.array(centers), np.array(scores)

def detect_swim_segments(acc, threshold=0.6, min_segment_s=8.0, merge_gap_s=6.0):
    """Returns list of (start_s, end_s) tuples for detected swimming activity."""
    tilt = tilt_signal(acc)
    centers, scores = sliding_regularity(tilt)
    is_swim = scores < threshold

    raw_segments = []
    cur_start = None
    for i, sw in enumerate(is_swim):
        if sw and cur_start is None:
            cur_start = centers[i]
        elif not sw and cur_start is not None:
            raw_segments.append([cur_start, centers[i]])
            cur_start = None
    if cur_start is not None:
        raw_segments.append([cur_start, centers[-1]])

    # merge segments separated by a short gap (e.g. a brief pause mid-swim)
    merged = []
    for seg in raw_segments:
        if merged and seg[0] - merged[-1][1] <= merge_gap_s:
            merged[-1][1] = seg[1]
        else:
            merged.append(seg)

    return [(s,e) for s,e in merged if e-s >= min_segment_s]

def classify_stroke_type(acc_window, threshold_alt_diff=12.0):
    """Classifies a detected swim segment as 'brasse' or 'crawl' based on the
    alternating-peak-height asymmetry: breaststroke is bilateral/symmetric
    (small difference between consecutive peaks), crawl seen from one arm is
    inherently asymmetric (pull phase vs recovery phase differ a lot).

    Validated on real data (29/09): brasse ~4.3, crawl ~24-39 — clean gap,
    threshold set at the midpoint-ish, biased toward not over-calling crawl.
    Status: exploratory, only 2 examples per class so far.
    """
    tilt = tilt_signal(acc_window)
    peaks, _ = find_peaks(tilt, prominence=8)
    heights = tilt[peaks]
    if len(heights) < 5:
        return 'indéterminé', None
    odd, even = heights[0::2], heights[1::2]
    n = min(len(odd), len(even))
    alt_diff = np.abs(odd[:n] - even[:n]).mean()
    stroke = 'crawl' if alt_diff > threshold_alt_diff else 'brasse'
    return stroke, alt_diff
