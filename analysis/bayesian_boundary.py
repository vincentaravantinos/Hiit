"""
Détection bayésienne des frontières réelles d'un exercice (début/fin), pour
remplacer une marge de transition fixe par une estimation qui combine :
  - un prior (l'estimation verbale de l'utilisateur, avec incertitude — ex.
    marge de fin ~4.5s ± 2s, marge de début très variable selon l'exercice)
  - une vraisemblance basée sur le signal (où le motif régulier commence/
    s'arrête réellement, via la régularité des intervalles entre pics)

Statut : exploratoire. Validé avec succès sur un cas "normal" (tractions
round 2, 19/09 — le postérieur retrouve quasi exactement la frontière trouvée
manuellement). Échoue proprement (grande incertitude plutôt qu'une fausse
réponse confiante) sur un cas atypique où il n'y a pas de frontière nette
(levées de jambes round 4, 20/09, interruption au milieu de la série).

Dépend de compute_tilt() de auto_axis_detect.py pour le signal par défaut ;
peut aussi être appelé sur un axe brut spécifique si besoin.
"""
import numpy as np
from scipy.ndimage import gaussian_filter1d
from scipy.signal import find_peaks
from scipy.stats import norm

FS = 52.0

def regularity_score(signal_segment):
    """Returns a 'badness' score: lower = more regular/periodic (real reps), higher = irregular/transition.
    Based on peak-interval coefficient of variation, penalized if too few peaks to judge."""
    if len(signal_segment) < int(FS*1.5):
        return 2.0  # too short to judge — mildly bad, not catastrophic
    smooth = gaussian_filter1d(signal_segment, sigma=0.15*FS)
    rng = smooth.max() - smooth.min()
    if rng < 3:  # essentially flat — could be genuine stillness (bad, not "reps") or a very calm exercise
        return 1.5
    prominence = max(rng * 0.15, 1e-6)
    peaks, _ = find_peaks(smooth, prominence=prominence)
    if len(peaks) < 2:
        return 1.5
    intervals = np.diff(peaks) / FS
    if len(intervals) < 2:
        return 1.0
    cv = intervals.std() / max(intervals.mean(), 1e-6)
    return cv

def bayesian_boundary(tilt_signal, search_center_s, search_radius_s, prior_mean_s, prior_std_s,
                       side='trailing', step_s=0.25, likelihood_window_s=8.0, temperature=0.3):
    """
    tilt_signal: full tilt array for the whole recording (or a generous superset window)
    search_center_s: the nominal phase boundary time (in the same time axis as tilt_signal indices/FS)
    side: 'trailing' (boundary is somewhere before search_center, real reps are BEFORE it)
          'leading' (boundary is somewhere after search_center, real reps are AFTER it)
    Returns: dict with posterior mean, MAP estimate, posterior std, and the raw grid for inspection.
    """
    candidates = np.arange(-search_radius_s, search_radius_s + step_s, step_s)
    log_prior = norm.logpdf(candidates, loc=(-prior_mean_s if side=='trailing' else prior_mean_s), scale=prior_std_s)
    # (prior is expressed as "offset from search_center towards the real data", sign convention below)

    log_likelihood = np.zeros(len(candidates))
    win_n = int(likelihood_window_s * FS)
    for i, c in enumerate(candidates):
        boundary_idx = int((search_center_s + c) * FS)
        if side == 'trailing':
            # regular reps should be found just BEFORE the boundary, irregular/transition just AFTER
            before = tilt_signal[max(0,boundary_idx-win_n):boundary_idx]
            after = tilt_signal[boundary_idx:boundary_idx+win_n]
        else:
            before = tilt_signal[max(0,boundary_idx-win_n):boundary_idx]
            after = tilt_signal[boundary_idx:boundary_idx+win_n]
        if len(before) < 10 or len(after) < 10:
            log_likelihood[i] = -10
            continue
        # trailing: want LOW badness before, HIGH badness after (regular->irregular transition)
        # leading: want HIGH badness before, LOW badness after (irregular->regular transition)
        if side == 'trailing':
            fit = regularity_score(after) - regularity_score(before)  # want this to be large positive
        else:
            fit = regularity_score(before) - regularity_score(after)  # want this to be large positive
        log_likelihood[i] = fit / temperature

    log_post = log_prior + log_likelihood
    log_post -= log_post.max()
    post = np.exp(log_post)
    post /= post.sum()

    map_idx = np.argmax(post)
    mean_offset = np.sum(candidates * post)
    std_offset = np.sqrt(np.sum(((candidates - mean_offset)**2) * post))

    return {
        'map_offset_s': candidates[map_idx],
        'mean_offset_s': mean_offset,
        'std_offset_s': std_offset,
        'candidates': candidates,
        'posterior': post,
    }
