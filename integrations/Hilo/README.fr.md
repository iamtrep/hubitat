<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Intégration Hilo

*[English version](README.md)*

Intégration parent/enfant non officielle pour [Hilo](https://www.hiloenergie.com/), le service d'énergie résidentielle d'Hydro-Québec. L'application de gestion se connecte à un compte Hilo, lit la consommation horaire d'un emplacement et garde tout l'historique dans File Manager. Un appareil enfant affiche l'énergie du jour et, envoyée par Hilo environ une fois par minute, la puissance de tout le domicile au compteur.

> **Note :** cette intégration n'est ni affiliée à Hilo ou à Hydro-Québec, ni approuvée par eux. Signalez les problèmes ici, pas à Hilo ni à Hydro-Québec.

## Composants

| Composant | Type | Description |
|---|---|---|
| **Hilo Manager** | Application | Connexion à Hilo, lecture horaire de la consommation, fichiers d'historique dans File Manager |
| **Hilo Energy Meter** | Pilote | Un appareil par emplacement : énergie d'aujourd'hui, d'hier et de la dernière heure, puissance en direct |
| **Hilo Winter Credit** | Pilote | Facultatif, un appareil par emplacement : l'énergie de référence du prochain événement de pointe et le plan pour les heures qui le précèdent |
| `hilo-history.html` | Page | Graphiques des fichiers d'historique; servie par File Manager |
| `hilo-winter-credit.html` | Page | Facultatif : les événements de pointe de chaque hiver, les énergies de référence et l'énergie effacée; servie par File Manager |

## Installation

### 1. Ajouter le code

Dans **Apps Code**, cliquez sur **New App**, puis **Import**, et collez :

```
https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/Hilo/HiloManager.groovy
```

Enregistrez. Faites de même dans **Drivers Code** (**New Driver** → **Import**) avec :

```
https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/Hilo/HiloEnergyMeter.groovy
```

Pour le [crédit hivernal](#crédit-hivernal), ajoutez aussi ce pilote :

```
https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/Hilo/HiloWinterCredit.groovy
```

### 2. Ajouter l'application

**Apps** → **Add User App** → **Hilo Manager**. La page a deux étapes. (Les captures d'écran montrent la page en anglais; elle s'ouvre en français.)

![Page de connexion de Hilo Manager : étape 1, le lien de connexion; étape 2, le champ pour l'adresse collée](images/hilo-login-1-app.png)

### 3. Se connecter à Hilo

Cliquez sur **Se connecter à Hilo**. La page de connexion de Hilo s'ouvre dans un nouvel onglet, à `connexion.hiloenergie.com`. Connectez-vous avec le courriel et le mot de passe de votre compte Hilo.

![La page de connexion de Hilo, avec connexion.hiloenergie.com dans la barre d'adresse](images/hilo-login-2-hilo.png)

### 4. Copier l'adresse de la page où vous arrivez

Après la connexion, le navigateur arrive sur une page de **my.home-assistant.io**. Elle peut proposer de lier le compte à Home Assistant, ou dire que le lien n'est pas configuré. Ignorez-la et **ne cliquez sur rien** : le code de connexion est dans l'adresse de la page, et c'est tout ce dont l'application a besoin.

Copiez l'adresse complète dans la barre d'adresse du navigateur :

![La page de my.home-assistant.io : copiez l'adresse complète dans la barre d'adresse; ne cliquez ni sur Link account ni sur Decline](images/hilo-login-3-copy-url.png)

### 5. La coller

De retour dans l'application, collez l'adresse à l'étape 2 et appuyez sur Entrée. L'application se connecte et lit l'emplacement du compte; si le compte en a plusieurs, choisissez-en un. L'application crée alors l'appareil et commence la lecture, sans attendre **Done**. Appuyez sur **Done** pour fermer la page.

L'adresse ne sert qu'une fois. Si l'application signale une erreur de connexion, cliquez de nouveau sur **Se connecter à Hilo** pour en obtenir une nouvelle.

L'application est renommée « Hilo Manager - <emplacement> »; changez le nom sous **Nom de cette instance** dans la page de l'application. L'appareil s'appelle « Hilo <emplacement> ». L'application lit ensuite l'historique du compte dans File Manager en arrière-plan (voir [Fichiers d'historique](#fichiers-dhistorique)).

Une instance sert un emplacement Hilo. Pour un deuxième compte ou emplacement, ajoutez une autre instance. Un emplacement déjà servi par une autre instance est refusé.

## Langue

La page de l'application, les descriptions d'événements et les réglages des appareils, les notifications et les deux pages HTML sont en français ou en anglais. **Langue / Language**, en haut de la page de l'application, fait le choix; elle commence en français. Les appareils suivent tout de suite; les pages suivent quand elles sont ouvertes par les boutons de la page de l'application ou, ouvertes directement, d'après `hilo-<emplacement>-ui.json` dans File Manager. Les journaux sont toujours en anglais. Les écrans et les boutons de Hubitat (**Done**, **File Manager**) restent en anglais.

## Connexion

Hilo n'a pas de connexion à lui pour les applications tierces; l'application utilise donc le client public de l'intégration Home Assistant. Sa seule adresse de retour enregistrée est my.home-assistant.io, d'où l'étape du collage. Aucune installation ni aucun compte Home Assistant n'est requis. my.home-assistant.io reçoit le code de connexion dans l'adresse de la page, comme pour les utilisateurs de Home Assistant, mais le code ne sert à rien sans le vérificateur PKCE que seule l'application détient.

Le jeton d'actualisation est enregistré chiffré et renouvelé à chaque usage. Le jeton d'accès dure une heure et n'est gardé qu'en mémoire : un redémarrage ou une mise à jour du code coûte une actualisation. Si Hilo refuse la connexion enregistrée (par exemple après un changement de mot de passe, ou après environ 14 jours sans que le hub joigne Hilo), l'application journalise une erreur, arrête la lecture, marque l'appareil hors ligne et affiche de nouveau le lien de connexion.

**Se déconnecter de Hilo**, sous **Compte**, oublie les jetons et arrête la lecture et le flux en direct.

## Appareil

### Hilo Energy Meter

- `energy` — kWh consommés aujourd'hui, depuis minuit heure locale
- `energyYesterday` — kWh consommés hier, inscrits quand les 24 heures sont arrivées
- `energyLastHour` / `lastHourStart` — kWh consommés pendant la dernière heure publiée par Hilo, et le début de cette heure
- `power` — la puissance du domicile en W, envoyée par Hilo environ une fois par minute
- `healthStatus` — `online` / `offline`. La description de l'événement en donne la raison.
- `lastUpdate` — moment de la dernière lecture de la consommation

Les noms et les valeurs des attributs restent en anglais, quelle que soit la langue : les règles les comparent.

Hilo publie la consommation d'une heure avec un délai d'une heure ou plus; `energy` est donc en retard sur le compteur. **Refresh** relit la consommation et rouvre le flux en direct s'il s'est tu.

Le réglage **Puissance en direct de Hilo** (activé par défaut) garde une connexion ouverte avec Hilo pour `power`. Désactivez-le pour ne garder que les lectures horaires.

## Lecture

La consommation est lue une fois par heure, à une minute entre :10 et :50 tirée au hasard à chaque enregistrement des réglages de l'application, pour que les hubs n'appellent pas tous Hilo en même temps. **Refresh** sur l'appareil la lit tout de suite.

Quand Hilo est injoignable, chacune des deux premières lectures échouées journalise un avertissement. La troisième journalise une erreur et marque l'appareil hors ligne, puis un rappel suit chaque heure tant que la panne dure. La première lecture réussie journalise le rétablissement et marque l'appareil en ligne. Quand Hilo répond avec `Retry-After`, la lecture s'arrête pendant ce délai, jusqu'à une heure.

Le flux en direct est rouvert avec un nouveau jeton toutes les 50 minutes. Quand il tombe ou reste silencieux 5 minutes, il se reconnecte après un délai qui double de 5 s à 5 min. Après 3 reconnexions échouées de suite, l'appareil est marqué hors ligne jusqu'au retour de la puissance.

## Fichiers d'historique

L'application garde la consommation horaire de chaque jour dans File Manager, un fichier par mois :

```
hilo-<id de l'emplacement>-energy-YYYY-MM.json
```

```json
{
  "unit": "kWh",
  "location": "<id de l'emplacement>",
  "days": {
    "2026-10-20": [ { "start": "2026-10-20T00:00:00-04:00", "kWh": 1.512 }, ... ]
  }
}
```

Un jour est inscrit quand toutes ses heures sont arrivées. Une heure sans valeur chez Hilo vaut `null`.

À la première configuration, l'application cherche où commence l'historique du compte, puis le lit dans les fichiers à raison d'un jour par minute : une année prend environ six heures. La page de l'application indique combien de jours restent.

### Parcourir l'historique

`hilo-history.html` est une page qui présente les fichiers en graphiques : les totaux quotidiens, les heures de chaque jour, la journée moyenne heure par heure avec les plages des événements de pointe (6 h–9 h et 16 h–20 h) marquées, et un tableau des mois. Téléversez-la dans File Manager (**Settings → File Manager**); la page de l'application affiche alors un bouton **Parcourir l'historique**, ou ouvrez `http://<hub>/local/hilo-history.html`. Elle lit les fichiers du hub qui la sert. Avec un accès à Internet, elle superpose aussi la température extérieure horaire à l'emplacement du hub (Settings → Hub Details), tirée des données historiques modélisées d'[Open-Meteo](https://open-meteo.com/en/docs/historical-weather-api), et ajoute un graphique de la consommation quotidienne selon la température extérieure moyenne. Elle marque aussi les événements de pointe réels d'Hydro-Québec, d'après ses [données ouvertes](https://donnees.hydroquebec.com/explore/dataset/evenements-pointe/) pour le crédit hivernal résidentiel (offre CPC-D, la source qu'utilise l'application Hydro Peak Events) : une marque sur chaque jour d'événement, les heures de l'événement ombrées en violet dans le graphique du jour, les jours d'événement colorés dans le graphique des températures, et la journée moyenne tracée à part pour les jours d'événement. Les plages de 6 h–9 h et 16 h–20 h sont ombrées du 1er décembre au 31 mars. Glissez sur des jours du graphique quotidien pour zoomer tous les graphiques et tableaux sur cette période; **Zoom arrière** (ou Échap) revient d'un cran, et choisir un mois dans le tableau compte aussi pour un cran. Sans accès à Internet, la page n'affiche que les graphiques d'énergie. De décembre à mars, et les jours d'événement, le graphique d'un jour mène au même jour dans la [page du crédit hivernal](#page-du-crédit-hivernal).

Lisez un fichier depuis une autre application ou un script avec `downloadHubFile`, ou en HTTP à `http://<hub>/local/<nom du fichier>`.

## Crédit hivernal

Pour un domicile au tarif D avec l'option de crédit hivernal d'Hydro-Québec. Pendant un événement de pointe, le crédit paie chaque kWh sous une cible : l'*énergie de référence* d'Hydro-Québec, la « limite de consommation » de l'application Hilo. Les attributs de l'appareil disent *target*; la page du crédit hivernal emploie les termes d'Hydro-Québec. Hydro-Québec n'affiche pas l'énergie de référence avant l'événement, et au-delà de 40 kWh d'énergie effacée elle peut baisser. L'application la calcule d'avance à partir de l'historique.

### La règle

D'après les Tarifs d'électricité d'Hydro-Québec, chapitre 2, article 2.59 :

- **Base :** pour chacune des 4 heures de l'événement, la moyenne de cette heure sur les 5 jours précédents du même type (jours de semaine ou jours de fin de semaine) sans événement de pointe, exclusion faite des valeurs minimale et maximale. Ces jours forment la *période de référence*. Les jours sans heures de pointe (24, 25, 26 et 31 décembre, 1er et 2 janvier, Vendredi saint, lundi de Pâques) ne sont pas des jours de référence.
- **Période d'ancrage** (terme du dossier d'Hydro-Québec) : 1 h–4 h avant un événement du matin, 12 h–14 h avant un événement du soir. Sa référence se calcule de la même façon.
- **Énergie de référence** (la « limite de consommation originale » de l'application Hilo) = base + (consommation de la période d'ancrage − sa référence) × 4/3 pour un événement du matin, × 2 pour un du soir. Le second terme est l'*ajustement pour la température*.
- **Limite :** quand l'*énergie effacée* dépasse 40 kWh, l'ajustement pour la température est *limité* à 2 × la référence de la période d'ancrage × le même facteur. L'énergie de référence devient alors l'**énergie de référence avec la limite** (la « limite de consommation ajustée » de l'application Hilo), base + 2 × la référence de la période d'ancrage × le facteur, quand c'est plus bas. Elle est connue la veille au soir; l'énergie de référence sans limite, seulement à la fin de la période d'ancrage.

Le calcul reproduit les énergies de référence d'Hydro-Québec de l'hiver 2025–2026 à 0,1 kWh près, sauf la consommation de la période d'ancrage de l'événement lui-même, où les valeurs horaires de Hilo et celles d'Hydro-Québec peuvent différer d'environ un kWh.

Tout sauf la consommation de la période d'ancrage est connu la veille au soir; le plan l'est donc aussi :

- **Énergie de référence avec la limite d'au moins 40 kWh au-dessus de ce que le domicile consomme chauffage coupé** (le *plancher*) : réduisez au maximum pendant l'événement. Une consommation de la période d'ancrage d'au moins 3 × sa référence porte l'énergie de référence à l'énergie de référence avec la limite; davantage ne change rien.
- **Énergie de référence avec la limite plus basse que ça :** consommez **au plus** le budget de la période d'ancrage pendant celle-ci, ce qui fixe l'énergie de référence au plancher + 40, et faites le reste du préchauffage après (4 h–6 h ou 14 h–16 h ne comptent pas). Réduire au maximum efface alors exactement 40 kWh.

### Réglages

Sous **Réglages du crédit hivernal** dans la page de l'application :

- **Calculer l'énergie de référence de chaque événement de pointe la veille au soir** active la fonction et crée l'appareil Hilo Winter Credit.
- **kWh consommés par le domicile pendant un événement de 4 heures, chauffage coupé** : le plancher, 3 kWh par défaut.
- **Notifier** et **Notification du soir à** (22:00 par défaut) : la veille d'un événement, une notification avec l'énergie de référence avec la limite et le plan pour la période d'ancrage. Avec **Notifier chaque soir d'hiver, même sans événement**, une chaque soir de décembre à mars, comme si le lendemain matin était un événement.
- **Événements absents des données ouvertes d'Hydro-Québec** : les données ouvertes ont manqué un événement; ajoutez-le sous la forme `AAAA-MM-JJ AM` ou `PM`, séparés par des virgules. Un événement passé manquant change les jours de référence.
- **Calculer maintenant** calcule tout de suite, en toute saison.

Les événements de pointe viennent des [données ouvertes](https://donnees.hydroquebec.com/explore/dataset/evenements-pointe/) d'Hydro-Québec (offre CPC-D), lues deux fois par jour à la même minute, entre 15:15 et 15:29 et entre 21:15 et 21:29. Les fenêtres d'hier matin à demain soir sont recalculées après chaque lecture horaire, toute l'année, et une minute après la fin de chaque événement. Une minute après la fin de chaque période d'ancrage un jour d'événement, l'application recalcule avec la consommation de cette période et envoie une notification avec l'énergie de référence et ce qui paie : réduire jusqu'au plancher, ou consommer juste assez pour effacer exactement 40 kWh quand la limite ramènerait autrement sous 40 kWh une réduction profonde. 

La carte **Crédit hivernal** de la page de l'application liste les fenêtres terminées d'hier et d'aujourd'hui avec leur résultat (énergie consommée contre l'énergie de référence, énergie effacée, crédit), puis la fenêtre en cours et la suivante. Celle en cours dit quoi faire maintenant : le plan, le compte de la période d'ancrage contre ce plan, le conseil jusqu'au début de l'événement, puis le compte de l'événement avec une projection à la puissance actuelle. Une fenêtre sans événement annoncé est une simulation, en gris; le jour d'un événement annoncé, un bandeau orange affiche un compte à rebours. Les résultats restent provisoires tant que Hilo n'a pas publié les heures de l'événement. Hors saison (d'avril à novembre), la carte l'indique. La puissance, les comptes et les comptes à rebours se mettent à jour en direct tant que la page est ouverte, par le flux d'événements du hub.

### Appareil Hilo Winter Credit

Affiche le prochain événement annoncé, ou la fenêtre en cours ou suivante comme si c'en était un, et le résultat de la dernière fenêtre terminée :

- `eventDate`, `window` (`AM` / `PM`), `announced` (`true` / `false`)
- `base`, `anchorReference`, `cappedTarget` — kWh
- `anchorPlan` (`at most` / `at least`) et `anchorTarget` — kWh à consommer pendant la période d'ancrage
- `expectedCurtailment` — kWh effacés si le plan est suivi et l'événement réduit jusqu'au plancher
- `anchorUsed` / `anchorWindow` — l'énergie de la période d'ancrage jusqu'ici, comptée d'après la puissance en direct, pendant 1 h–4 h et 12 h–14 h
- `eventUsed` — l'énergie de l'événement jusqu'ici, comptée de la même façon, pendant 6 h–10 h et 16 h–20 h
- `target`, `advice` (`pending` / `cut` / `keep`), `keepUse` — à la fin de la période d'ancrage. `keep` signifie que consommer `keepUse` kWh pendant l'événement garde 40 kWh effacés et paie plus que réduire jusqu'au plancher.
- `referenceDays`, `summary` (ce dernier dans la langue choisie)
- `lastWindow` (`AAAA-MM-JJ AM` / `PM`), `lastAnnounced` (`true` / `false`), `lastConsumed`, `lastCurtailed` — kWh, `lastCredit` — $, `lastFinal` (`false` tant qu'il repose sur le compte en direct plutôt que sur les heures de Hilo)

Une règle peut arrêter le préchauffage quand `anchorPlan` vaut `at most` et que `anchorUsed` atteint `anchorTarget`. **Refresh** recalcule.

Le dernier calcul, données d'entrée comprises, est dans File Manager sous `hilo-<id de l'emplacement>-peak-target.json`.

### Page du crédit hivernal

`hilo-winter-credit.html` affiche un hiver (de décembre à mars) à la fois, dans les termes des Tarifs d'électricité d'Hydro-Québec (article 2.59). Avec un accès à Internet, elle ajoute la température extérieure horaire à l'emplacement du hub, d'après Open-Meteo, comme la page de l'historique : sous chaque date du calendrier, en ligne dans le graphique du jour, et en moyenne des heures de la période d'ancrage et de l'événement pour chaque jour de référence et pour le jour de l'événement, pour vérifier un ajustement pour la température selon la météo. Téléversez-la dans File Manager à côté de `hilo-history.html`; la page de l'application affiche alors un bouton **Analyse du crédit hivernal** dans la carte **Crédit hivernal**, ou ouvrez `http://<hub>/local/hilo-winter-credit.html`. Elle a besoin d'Internet pour la liste des événements d'Hydro-Québec, et ajoute les événements inscrits sous **Événements absents des données ouvertes d'Hydro-Québec**.

- Tuiles : les événements de pointe de l'hiver, l'énergie effacée, les événements où l'ajustement pour la température a été limité, et le crédit avant taxes, au tarif en vigueur à la date de chaque événement (aucun affiché avant avril 2025). Seuls comptent les événements dont toutes les heures sont au dossier.
- Un calendrier de l'hiver avec les événements marqués. Cliquez sur un jour pour voir :
  - ses heures : la période d'ancrage (1 h–4 h ou 12 h–14 h) avec la moyenne de chaque heure sur la période de référence et la consommation au-dessus, qui fait monter l'énergie de référence; les heures de l'événement avec la part de chaque heure dans l'énergie de référence et l'énergie effacée en dessous;
  - l'énergie effacée et le crédit, et si la limite de l'ajustement pour la température s'est appliquée;
  - un tableau de trois choix pour la consommation pendant l'événement (réduire jusqu'au plancher, telle que consommée, juste assez pour effacer exactement 40 kWh), chacun avec son énergie effacée, son crédit, le coût de l'énergie consommée au-dessus du plancher, le crédit moins ce coût, et l'écart avec la réduction jusqu'au plancher;
  - l'événement heure par heure, en cumul : la consommation contre l'énergie de référence, avec et sans la limite;
  - un diagramme de flux : la période de référence et l'ajustement pour la température forment l'énergie de référence, qui se partage entre la consommation et l'énergie effacée; la part de l'ajustement au-delà de la limite est montrée à part;
  - sous l'explication du calcul d'Hydro-Québec, l'arithmétique et la période de référence : les valeurs de chaque heure avec le minimum et le maximum barrés.

  Un jour sans événement est calculé comme s'il en avait un; choisissez l'événement du matin ou du soir au-dessus du graphique.
- L'énergie effacée à chaque événement, par rapport à 40 kWh, et un tableau de tous les événements avec leur énergie de référence, l'énergie de référence avec la limite, la consommation et l'énergie effacée.

**Ouvrir dans l'historique** montre le même jour dans la page de l'historique. L'adresse garde le jour et l'événement (`#day=2026-01-24&start=6`), et la langue (`&lang=fr`), pour qu'un lien ou un signet s'ouvre dessus.

## Remerciements

L'identifiant client, la portée et la clé d'abonnement de l'API sont ceux de [python-hilo](https://github.com/dvd-dev/python-hilo) (MIT), la bibliothèque derrière l'intégration Hilo de Home Assistant. Aucun code source n'est repris.

## Licence

MIT — le texte complet de la licence est dans chaque fichier source.
