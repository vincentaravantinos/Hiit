# Analyse hors-ligne — comptage de répétitions

Code Python de recherche/validation pour l'algorithme de comptage de répétitions,
séparé de l'app réelle (`index.html`, en JavaScript). Voir la mémoire du projet
(`/projects/.../areas/polar-rep-counting.md`) pour le contexte complet — protocole
BLE, historique, statut général.

## Statut actuel (20/09/2026)

- **`core_offline_algorithm.py`** — l'algorithme "précis" actuellement utilisé par
  l'app réelle (tilt accéléromètre + seuils par percentile). Validé à 11/14 sur le
  jeu de référence (14 configs). C'est la base de référence, stable.
- **`auto_axis_detect.py`** et **`bayesian_boundary.py`** — développements du
  20/09, **exploratoires, pas encore intégrés dans l'app**. Amélioration nette
  observée sur 2 séances réelles complètes (squats, rowing, pompes surtout ;
  tractions et levées de jambes restent difficiles). À valider sur davantage de
  séances avant d'envisager une intégration dans `index.html`.

## Prochaines étapes (selon le plan convenu)

1. Continuer la collecte de séances réelles avec l'app (déjà équipée : ACC+GYRO+
   MAG+HR, position du bracelet enregistrée, journal de connexion exportable).
2. Valider/affiner `auto_axis_detect.py` et `bayesian_boundary.py` sur ces
   nouvelles séances.
3. Une fois stable : intégrer dans l'app (`index.html`), puis s'attaquer au HR et
   au comptage en direct.
4. Étape finale : comment exploiter ces données pour du coaching sportif utile.

## Leçons apprises à ne pas re-découvrir

- Chaque exercice a une dimension de mouvement privilégiée (ex. axe Z pour les
  levées de jambes) — le tilt combiné 3D peut la diluer si un mouvement plus
  ample (transition, descente) domine la fenêtre.
- Les tractions : le biceps (où est le capteur) tourne bien autour du coude
  pendant l'exercice — le capteur est donc bien placé, contrairement à une
  hypothèse initiale erronée.
- Le placement au début d'un exercice est beaucoup plus variable (jusqu'à 8s
  observés) que le placement en fin d'exercice (~4-5s, plus stable) — d'où
  l'intérêt d'un prior asymétrique plutôt qu'une marge fixe unique.
- Les mouvements explosifs (fatigue, tractions notamment) introduisent de la
  vraie accélération linéaire qui distord l'hypothèse "la norme reste ~1G" —
  limite connue, pas encore traitée spécifiquement.
- Les levées de jambes : le signal utile au bras est intrinsèquement très
  faible (mouvement dominant aux jambes) — accepté comme limite structurelle,
  pas une priorité à optimiser davantage.
