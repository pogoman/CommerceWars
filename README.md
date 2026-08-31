# Commerce Wars

Success paints a target. A standalone Starsector mod that keeps late-game player
polities in check: factions whose export business you muscle in on develop
grievances that escalate from diplomatic notes to ultimatums to enforcement
fleets. Comply, pay them off, or defy them and fight. Military buildup draws
the same attention. Grow strong enough and no single faction dares press a
demand alone... so they form coalitions. Vanilla API only; LunaLib optional
(full in-game config menu).

## The loop

1. **Grievance detection** (generic, faction-independent): every tick the mod
   recomputes sector export market shares. A faction develops a grievance over
   a commodity when it is a significant producer, your share is big enough to
   notice, and your share rivals theirs. The vanilla economy recomputes shares
   dynamically, so *compliance detection is automatic* - lose the share (however
   you like) and the numbers themselves cool off.
2. **Resentment** accrues monthly per contested commodity, shown as a
   colony-crisis-style event bar (0-600): diplomatic warning (200), formal
   ultimatum (400), enforcement (600). One intel screen per aggrieved faction;
   a coalition consolidates onto its leader's screen, partners' factors grouped
   by faction.
3. **Respond**: comply (drop your share below the notice line), pay one-off
   settlements that buy down accumulated resentment (a coalition must be paid
   as a bloc), take a commission to lay a military grievance to rest, or defy
   and fight.
4. **Enforcement**: strike fleets sized against your actual defenses - your
   personal fleet included, since they know you will defend in person - and
   bounded only by the faction's real military-industrial capacity: no magic
   fleets, but nothing held back either. Disrupting their war industry
   genuinely shrinks what they can send.

## Enforcement strikes

- **Adaptive payloads**: each fleet decides in real time - shell standing
  defenses open first (sound doctrine), disrupt the first still-operating
  industry on its hit list, fall back to bombardment or stockpile raids when
  nothing else remains. Fleet-by-fleet operations reports on every raid intel.
- **Multi-front coalition strikes**: each coalition member flies its own raid,
  under its own flag, against the player colony that most offends *its own*
  grievance - arrivals synchronized so you cannot beat them piecemeal. The
  operation is judged as a whole: one contingent wiped is a casualty report,
  not a defeat.
- **Sabotage window**: every strike opens with a planning phase at its staging
  market. Knock out that market's military base or high command in time and
  the contingent is aborted outright.
- **Item heists**: at high escalation, an oversized contingent storms the vault
  for an installed item (nanoforge, synchrotron...) while coalition partners
  raid diversionary fronts. Needs an overwhelming ground advantage; stolen
  items are installed on their own markets and can be raided back.
- **Ground truth**: raids only take or disrupt what they can actually reach -
  the same raid-strength-vs-defender-strength check the player's own raids
  face, planetary shield and garrison included.

## Strength gate and coalitions

Simple rules. A faction that can field enough force to crack the colony it
would strike - your own fleet included - presses solo: no coalition. One that
cannot pools only with allies who also cannot, behind a single leader. Too
weak even pooled: resentment simmers below the ultimatum line. Fieldable
force is real military infrastructure (garrisonless trade ports project no
force), so what passes the gate can always launch a credible strike. Defeat a
joint strike and partners can wash their hands of the alliance - demoralized
out of coalition pools and their own resentment vented. Outgrow every
plausible coalition and the demands stop: earned silence.

## Counterplay ladder

- Defeat strike fleets (resets their bar, but escalates the next attempt)
- Abort strikes at the source during the planning window
- Tac-bomb their competing production or war industry (shrinks their strikes)
- Settle, comply, or take a commission
- Sat-bomb a competitor into the ground - at vanilla atrocity prices, and the
  victim's grievance converts into a permanent **vendetta**: no settlements,
  no truces, saturation bombardment in return, ending only when one side has
  no worlds left
- **Total War** (config, off by default): strips story-critical protection
  from every market - any world can be bombed down and destroyed, factions can
  be entirely eliminated. Questlines anchored on destroyed worlds will strand.

Your own raids and bombardments against an aggrieved faction spike its
resentment instantly - and can trigger enforcement outright.

## Config

Everything is tunable via the LunaLib menu (detection thresholds, accrual
rates, strike scaling, coalition behavior, vendetta, Total War, debug tools).
Without LunaLib the mod uses `data/config/settings.json`.

## Build

`compile.ps1` (JDK 17 via JAVA_HOME, same as sibling mods Remnant Retribution
and Threat Incursion).
