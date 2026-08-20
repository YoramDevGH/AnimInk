# Recherche de latence sur Supernote Nomad

Analyse effectuée le 19 août 2026 sur le Supernote connecté
`SN078E10047118`. Cette note décrit des constats faits sur cet appareil et non
une API Android publique garantie par le fabricant.

## Atelier

L’APK installé est `com.ratta.supernote.paint`, Atelier 1.1.82. Il tourne avec
l’UID système 1000 et contient principalement deux bibliothèques natives :

- `libreadEvent.so`, dédiée à la lecture des événements d’entrée ;
- `libspaint_arm64-v8a.so`, le moteur de peinture C++.

Les symboles du moteur montrent l’emploi de libMyPaint (`mypaint_brush_*`,
`mypaint_surface_*`) et d’une surface tuilée. Le type de tuile contient
explicitement une taille de 128 pixels. La base `assets/brushes.db` contient
les réglages MyPaint des crayons, plumes, marqueurs, sprays et gommes.

Conclusion : Atelier ne redessine pas une grande Bitmap Android à chaque
`MotionEvent`. Il lit le stylet dans un chemin natif, rend le trait par petits
rectangles et conserve le document sous forme de tuiles raster.

## Moteur d’écriture du firmware

Le boot class path du Nomad contient notamment `htfyview.jar`, `htfypw.jar`,
`htfyOpt.jar` et `libeinkpwcoreapi.jar`, reliés à
`libeinkpwcorejni.so`. Une `View` du firmware expose `getPWInterFace()`, qui
crée un contrôleur `htfyun.penwrite.ctrl.PWCoreCtrl`.

Le contrôleur testé sur l’appareil est en version
`V2.4.7-20240911-zt`. Il possède :

- une boucle d’entrée stylet distincte de la boucle UI Android ;
- un bitmap d’écriture superposé à la `View` ;
- des mises à jour e-ink natives limitées aux rectangles sales ;
- un callback de fin de trait donnant le bitmap et le rectangle modifié ;
- des modes crayon, plume et gomme.

Un APK isolé ciblant Android 35 a obtenu ce contrôleur sans UID système et a
reçu un bitmap de trait valide. Cela permet de bénéficier du chemin rapide du
firmware sans copier les bibliothèques propriétaires dans l’application.

## Architecture AnimInk 0.4

Pendant que le stylet touche l’écran, `SupernotePenEngine` laisse le moteur du
firmware afficher directement le trait. La boucle UI d’AnimInk ne calcule ni
ne sérialise le document. À la levée du stylet, `InkCanvasView` recopie le seul
rectangle modifié dans les tuiles 128 × 128 de la frame courante.

Changer de frame compose une image de fond une seule fois et la donne au
moteur natif. La lecture désactive temporairement l’écriture directe. Sur un
Android sans ces extensions Supernote, le code conserve un chemin de dessin
Android classique.

## Vérifications

- initialisation réussie depuis une application ordinaire ciblant SDK 35 ;
- callback natif de fin de trait reçu avec un rectangle sale valide ;
- récupération d’un trait de test : 9 tuiles enregistrées, au lieu des 84
  tuiles de toute la page avec l’ancienne capture intégrale ;
- compilation et lint Android sans erreur ;
- aucun service, réseau ou traitement périodique ajouté.

La latence physique stylet-écran ne peut pas être chiffrée fidèlement par ADB :
il faudrait une caméra haute vitesse. Le changement important est néanmoins
structurel et vérifiable : l’affichage pendant le trait ne dépend plus du
thread UI ni du rendu raster Java d’AnimInk.

## Références publiques

- documentation développeur Supernote : <https://docs.supernote.com/en> ;
- présentation officielle d’Atelier :
  <https://support.supernote.com/en_US/Tools-Features/introducing-atelier-the-drawing-app> ;
- exemple public d’une architecture e-ink comparable :
  <https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Onyx-Pen-SDK.md>.
