# Commerce Wars

Success paints a target. A standalone Starsector mod that keeps late-game player
markets in check: factions whose export business you muscle in on develop
grievances that escalate from diplomatic notes to ultimatums to enforcement
fleets. Comply, pay tribute, or defy them and fight. Sibling of Remnant
Retribution and Threat Incursion (vanilla API only, LunaLib optional).

## Core loop

1. **Grievance detection** (generic, faction-independent): every tick the mod
   recomputes sector export market shares. A faction develops a grievance over
   a commodity when it is a significant producer (top-N, minimum share), your
   share is big enough to notice, and your share rivals theirs. The vanilla
   economy recomputes shares dynamically, so *compliance detection is
   automatic* - shut the industry down and the numbers themselves cool off.
2. **Resentment** accrues monthly per contested commodity
   (weightMult x yourShare/theirShare, capped), shown as a colony-crisis-style
   event bar (0-600) with stages: warning (200), ultimatum (400),
   enforcement (600).
3. **Response options** (Phase 2): comply (drop your share below the notice
   line - however you like), pay tribute (recurring cut of contested export
   income, freezes resentment), or defy (fight the enforcement fleets).
4. **Enforcement** (Phase 2/3): raids that steal stockpiles, tactical
   bombardment that disrupts the offending industries, and at high escalation
   a chance to *steal installed items* (synchrotron, nanoforge...) - stolen
   items go somewhere real and can be raided back.
5. **Military track** (Phase 4): same loop, but the metric is your military
   infrastructure (High Command, Star Fortress, Orbital Works + nanoforge) and
   the demand is a downgrade.
6. **Strength gate + coalitions** (Phase 5): factions only press demands while
   militarily on par or superior. Outgrow them and aggrieved factions must
   pool their strength into joint ultimatums - mixed enforcement fleets,
   tribute split among signatories, coalition resolve that cracks when you
   defeat them. Outgrow *plausible coalitions* and the demands stop: earned
   silence.
7. **Counterplay ladder**: defeat fleets (vents some resentment, escalates the
   next strike), tac-bomb their competing production back (delays), sat-bomb
   -> decivilize -> recolonize the ruins (grievance mathematically evaporates
   with the competitor - at vanilla atrocity prices).

## Status

- [x] Phase 1: share tracker, per-faction grievance intel (crisis-style
      screen), warning/ultimatum stages, automatic compliance detection,
      calm-based expiry, debug mode (LunaLib menu: reveal all stats, force
      grievance, force ultimatum, 10x clock, verbose logging)
- [x] Phase 2: ultimatum orders dialog (tribute/defy; comply is physical),
      monthly tribute payments (freeze accrual, lapse if unpayable),
      enforcement strikes (disruption raids vs offending industries),
      tactical bombardment at escalation, stockpile theft from storage on
      strike success, post-strike truce, defeat venting + escalation,
      force-strike debug toggle
- [x] Phase 3: item heists (marine-constrained ground ops, steal-back via raids)
- [x] Phase 4: military threat track (arsenal rivalry causes, commission response, military-target strikes)
- [x] Phase 5: strength gate (pooled military scores vs gateRatio x player), coalitions (mutually non-hostile aggrieved factions; mixed-roster fleets via CoalitionRaidFGI; pooled strike capacity; defeat demoralizes partners out of the pool), earned-silence endgame (gated grievances hold below the ultimatum line)
- [x] Phase 6: counterplay accounting - player raids/bombardments spike the target grievance (and can trigger enforcement outright); saturation bombardment ripples (emboldened rivals spike, weak ones cowed out of coalition pools); single guarded enforcement-launch path enforcing the strike cooldown properly. Deciv venting proved emergent (live share/score math). Vanilla relations untouched by design

## Vanilla reference points (mods/.api-src)

- `PunitiveExpeditionManager.getExpeditionReasons()` - the share-comparison
  idiom (retired vanilla system this mod resurrects)
- `BaseEventIntel` / `HostileActivityEventIntel` - the event screen framework
- `HegemonyInspectionIntel` + `HIOrdersInteractionDialogPluginImpl` - the
  demand-with-options pattern for Phase 2
- `GenericRaidFGI` / `FGRaidParams.setDisrupt()` / `.setBombardment()` -
  enforcement fleets for Phase 2/3

## Build

`compile.ps1` (JDK 17 via JAVA_HOME, same as sibling mods).


