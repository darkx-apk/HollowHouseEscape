# Yaliyoongezwa / kubadilishwa — Hollow House Escape

## Ghorofa mbili (kufuatana na ramani yako)
- **Floor 1 (chini):** mlango mkuu (front door), hallway ya kusini yenye "Bath"
  nook, na ukanda wa kaskazini wenye vyumba 4: Cellar (chini/"Down"), Room A,
  Room B, na Upstairs alcove. Kila chumba kina mlango wake kutoka ukanda —
  kama kwenye mchoro wako.
- **Floor 2 (juu):** unafika kwa kupanda ngazi kwenye "Upstairs" alcove.
  Kuna landing, ukanda mfupi, chumba kikubwa cha kulala (bed + desk),
  na closet iliyochongwa kona ya magharibi.
- Kutembea juu ya eneo la ngazi (Stairs Up / Stairs Down) kunakuhamisha
  ghorofa moja kwa moja.

Faili mpya: `Level.kt` — hapa ndipo ramani nzima ipo. Ukitaka kubadilisha
umbo la chumba, ongeza mlango, au kusogeza funguo, ni faili hili tu
unalohitaji kugusa.

## Milango ya kweli (siyo tundu tu ukutani)
- Ukikaribia mlango na kuutazama, kitufe cha mkono 🤚 kinatokea juu ya
  screen mahali mlango ulipo (siyo sehemu tuli — kinafuata mlango).
- Bonyeza mara moja = mlango unafunguka (una-animate kufunguka).
  Bonyeza tena = unafunga.
- Mlango uliofungwa unazuia mwendo (huwezi kupita), ukifunguka unapita kawaida.

## Kujificha chini ya meza / kitanda
- Kitufe kipya cha **Hide** (chini kushoto). Ukibonyeza, mchezaji anachuchumaa.
- Ukiwa umechuchumaa na uko ndani ya eneo la kitanda/meza (Bed/Desk), sanduku
  la fenicha halikuzuii tena — unaingia chini yake, na yule "stalker"
  hawezi kukuona hata akiwa karibu sana.
- Bonyeza Hide tena kusimama.

## Funguo tano — moja moja mlangoni
- Funguo 3 ziko Floor 1 (Cellar, Room A, Room B), funguo 2 ziko Floor 2
  (Landing closet, karibu na wardrobe chumbani).
- Ukiokota funguo, ina-"beba" tu (haifunguzi mlango moja kwa moja kama zamani).
- Ukifika mlango mkuu ukiwa na funguo, kitufe cha mkono kinaonyesha
  "Insert key (x/5)" — bonyeza kuingiza funguo moja. Rudia mchakato huu
  mpaka funguo zote 5 ziingizwe, ndipo mlango unafunguka kabisa.

## Madirisha na mwanga wa mwezi
- Madirisha yamewekwa kwenye kuta za nje (Floor 1: pande zote mbili za
  hallway; Floor 2: kuta za mashariki na magharibi za chumba cha kulala).
- Kila dirisha lina "mwanga" wake unaosambaa chumbani (shader mpya
  `uMoonPos` / `uMoonCount`) — hivyo mwanga wa mwezi unaonekana mbali
  na dirisha, siyo tu juu yake.

## Kuta zenye undani zaidi (detailed walls)
- Kila ukuta sasa una baseboard (chini) na trim (juu) ya rangi tofauti —
  vinaongezwa moja kwa moja kwenye `Level.kt` (function `addTrim`), hivyo
  kuta hazionekani flat tena.

## Vitu vingine
- HUD sasa inaonyesha: funguo zilizoingizwa/jumla, unazobeba, hali
  (Walking/Hidden/Crouching...), na ghorofa uliyopo (Ground/Upper Floor).
- `SoundManager.kt`, `CubeMesh.kt`, `TouchStick.kt`, `LookPad.kt`,
  `GameView.kt` — havikuguswa, bado vinafanya kazi kama awali.

## Ukiwa Android Studio
Fungua mradi kama kawaida, sync gradle, run. Hakuna dependency mpya
iliyoongezwa.
