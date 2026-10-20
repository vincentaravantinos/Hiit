# Évaluation du comptage de reps en direct (20/10/2026)

Rejoue chaque exercice des séances enregistrées toutes les 0,5 s en ne donnant à l'algo v3
que les données disponibles à cet instant, et compare au compte final.

1. Extraire le JS de l'app : les blocs `<script>` de `index.html` concaténés dans `all.js`.
2. Mettre les séances déchiffrées (`analysis/decrypt.py`) dans `data/` (jamais committées).
3. `node run.js` (≈ 8 min) → `live_results.json`, puis `python3 analyze.py` et `analyze2.py`.

Résultat sur 12 séances / 192 exercices : compte à t=30 s identique au final dans 99,5 % des cas ;
annonce « record » avec 1 s de confirmation : retard médian 1,1 s, 7 % de fausses annonces
(surtout tractions et rowing), 2 s de confirmation : 3 %.
