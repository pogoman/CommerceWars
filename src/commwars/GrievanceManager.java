package commwars;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

/**
 * The Commerce Wars clock. Transient (re-added by the mod plugin on every
 * game load); the grievance intel entries themselves persist in the save.
 *
 * Every tick: recompute market-share grievance causes, push fresh snapshots
 * into active grievance intels, open new grievances for the angriest
 * qualifying factions (up to the sector-wide cap), and expire calm ones.
 */
public class GrievanceManager implements EveryFrameScript {

	protected IntervalUtil interval = new IntervalUtil(1f, 1.2f);
	// the single faction allowed to front a coalition this tick (sector-wide)
	protected transient String tickBlocAnchor = null;
	// strikes get a fast clock so consequences (theft, truce, vent) land
	// right after the raid, not up to a week later
	protected IntervalUtil strikeInterval = new IntervalUtil(0.8f, 1.2f);
	protected boolean firstTick = true;

	@Override
	public boolean isDone() {
		return false;
	}

	@Override
	public boolean runWhilePaused() {
		return false;
	}

	@Override
	public void advance(float amount) {
		float days = Global.getSector().getClock().convertToDays(amount);

		strikeInterval.advance(days);
		if (strikeInterval.intervalElapsed() && CommWarsConfig.enabled()) {
			for (GrievanceEventIntel intel : getActiveGrievances().values()) {
				if (intel.getStrike() != null) {
					pollStrike(intel);
				}
			}
		}

		interval.advance(days);
		if (!interval.intervalElapsed() && !firstTick) return;
		firstTick = false;

		// re-arm to the configured tick length (allows live tuning via LunaLib)
		float tick = Math.max(1f, CommWarsConfig.tickDays());
		interval.setInterval(tick * 0.9f, tick * 1.1f);

		if (!CommWarsConfig.enabled()) return;

		if (CommWarsConfig.totalWar()) {
			stripStoryCritical();
		}
		trackEliminations();

		Map<String, List<ShareTracker.Cause>> all = ShareTracker.compute();
		Map<String, MilitaryScore.MilCause> mil = computeMilitaryCauses();
		Map<String, GrievanceEventIntel> active = getActiveGrievances();

		// one coalition anchor at a time, sector-wide (see computeBlocAnchor)
		tickBlocAnchor = CoalitionCalc.computeBlocAnchor(active.values());

		// update existing grievances with fresh cause snapshots
		for (GrievanceEventIntel intel : active.values()) {
			// a permanent pather agreement (the vanilla "holy peace until the
			// End of Days", however earned - including the planetkiller handover)
			// pacifies the Luddic Path for good: end their grievance and never
			// reopen it while the agreement holds
			if (isPatherPeaceActive(intel.getFactionId())) {
				intel.endForPatherPeace();
				continue;
			}
			intel.ensureFactors();
			intel.updateCauses(all.get(intel.getFactionId()));
			intel.setMilitaryCause(mil.get(intel.getFactionId()));
			intel.updateCommissionState();
			intel.validateSupportState();
			updateGate(intel, active.keySet());
			pollStrike(intel);
			rearmEnforcement(intel);
			// a bar pinned at 600 (cooldown, or a just-resolved strike) fires
			// the moment its blockers clear
			intel.tryLaunchEnforcement();
			intel.tickCalm();
			// a blood feud ends only when nothing remains to pursue it
			if (intel.isVendetta() && !intel.isEnding() && !intel.isEnded()
					&& Misc.getFactionMarkets(intel.getFaction(), null).isEmpty()) {
				intel.endVendetta();
			}
		}

		// open new grievances for the angriest qualifying factions
		final Map<String, Float> totals = new LinkedHashMap<String, Float>();
		for (Map.Entry<String, List<ShareTracker.Cause>> e : all.entrySet()) {
			totals.put(e.getKey(), ShareTracker.totalWeight(e.getValue()));
		}
		for (Map.Entry<String, MilitaryScore.MilCause> e : mil.entrySet()) {
			Float prev = totals.get(e.getKey());
			totals.put(e.getKey(), (prev == null ? 0f : prev) + e.getValue().weight);
		}

		List<String> candidates = new ArrayList<String>(totals.keySet());
		java.util.Collections.sort(candidates, new java.util.Comparator<String>() {
			@Override
			public int compare(String a, String b) {
				return Float.compare(totals.get(b), totals.get(a));
			}
		});

		// vendettas do not count against the ordinary-grievance cap
		int nonVendettaActive = 0;
		for (GrievanceEventIntel intel : active.values()) {
			if (!intel.isVendetta()) nonVendettaActive++;
		}

		for (String factionId : candidates) {
			if (nonVendettaActive >= CommWarsConfig.maxActiveGrievances()) break;
			if (active.containsKey(factionId)) continue;
			float total = totals.get(factionId);
			if (total < CommWarsConfig.startThreshold()) continue;
			// one campaign of coercion at a time: their vanilla colony crisis
			// already speaks for them
			if (VanillaCrisis.isActiveOrPending(factionId)) {
				CommWarsConfig.log("Skipping grievance for " + factionId
						+ ": vanilla colony crisis active/pending");
				continue;
			}
			// a patron does not open a dossier on its own commissioned client
			if (factionId.equals(Misc.getCommissionFactionId())) {
				CommWarsConfig.log("Skipping grievance for " + factionId
						+ ": player holds a commission with them");
				continue;
			}
			// the Path holds a permanent agreement with you - holy peace, no grievance
			if (isPatherPeaceActive(factionId)) {
				CommWarsConfig.log("Skipping grievance for " + factionId
						+ ": permanent pather agreement in effect");
				continue;
			}

			GrievanceEventIntel intel = new GrievanceEventIntel(factionId, all.get(factionId));
			intel.setMilitaryCause(mil.get(factionId));
			active.put(factionId, intel);
			nonVendettaActive++;
			CommWarsConfig.log("Opened grievance: " + factionId
					+ " (total weight " + total + ")");
		}

		handleDebugToggles(all, active);

		if (CommWarsConfig.debugLogging()) {
			logTickSummary(totals, active);
		}
	}

	/**
	 * Total War: strip the story-critical protection from every market, so
	 * any world can be bombed down and destroyed. The vanilla lifecycle
	 * plugin and active missions re-apply the flags (on load and mid-game),
	 * so this runs every tick rather than once.
	 */
	protected void stripStoryCritical() {
		int stripped = 0;
		for (com.fs.starfarer.api.campaign.econ.MarketAPI market
				: Global.getSector().getEconomy().getMarketsCopy()) {
			if (Misc.isStoryCritical(market)) {
				market.getMemoryWithoutUpdate().unset(
						com.fs.starfarer.api.impl.campaign.ids.MemFlags.STORY_CRITICAL);
				stripped++;
			}
		}
		if (stripped > 0) {
			CommWarsConfig.log("Total War: stripped story-critical protection from "
					+ stripped + " market(s)");
		}
	}

	/**
	 * Announce when a faction that held markets loses its last one - however
	 * that came to pass.
	 */
	@SuppressWarnings("unchecked")
	protected void trackEliminations() {
		Object val = Global.getSector().getPersistentData().get("commwars_hadMarkets");
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, Boolean>();
			Global.getSector().getPersistentData().put("commwars_hadMarkets", val);
		}
		Map<String, Boolean> had = (Map<String, Boolean>) val;

		for (FactionAPI faction : Global.getSector().getAllFactions()) {
			if (faction.isPlayerFaction()) continue;
			if (com.fs.starfarer.api.impl.campaign.ids.Factions.NEUTRAL
					.equals(faction.getId())) continue;
			boolean has = !Misc.getFactionMarkets(faction, null).isEmpty();
			Boolean before = had.get(faction.getId());
			if (has) {
				had.put(faction.getId(), true);
			} else if (Boolean.TRUE.equals(before)) {
				had.put(faction.getId(), false);
				CommWarsConfig.log("Faction eliminated as a sector power: " + faction.getId());
				com.fs.starfarer.api.impl.campaign.intel.MessageIntel msg =
						new com.fs.starfarer.api.impl.campaign.intel.MessageIntel(
								Misc.ucFirst(faction.getDisplayNameWithArticle())
								+ " has been eliminated as a sector power - not one of its "
								+ "worlds remains. History will remember who wrote the "
								+ "final chapter.", Misc.getTextColor(),
								new String[] { faction.getDisplayName() },
								faction.getBaseUIColor());
				String crest = faction.getCrest();
				if (crest != null) msg.setIcon(crest);
				Global.getSector().getCampaignUI().addMessage(msg);
			}
		}
	}

	/** Military-track causes: factions unnerved by the player's arsenal. */
	protected Map<String, MilitaryScore.MilCause> computeMilitaryCauses() {
		Map<String, MilitaryScore.MilCause> result =
				new LinkedHashMap<String, MilitaryScore.MilCause>();
		for (FactionAPI faction : Global.getSector().getAllFactions()) {
			if (faction.isPlayerFaction()) continue;
			if (com.fs.starfarer.api.impl.campaign.ids.Factions.NEUTRAL.equals(faction.getId())) continue;
			if (Misc.getFactionMarkets(faction, null).isEmpty()) continue;
			MilitaryScore.MilCause cause = MilitaryScore.computeCause(faction);
			if (cause != null) {
				result.put(faction.getId(), cause);
			}
		}
		return result;
	}

	/**
	 * The strength gate: a faction presses demands only while its military
	 * power - alone or pooled with a coalition of fellow aggrieved factions -
	 * is on par with the player's. Too weak even together: the grievance
	 * simmers below the ultimatum line. The earned-silence endgame.
	 */
	protected void updateGate(GrievanceEventIntel intel, java.util.Set<String> activeIds) {
		// membership and the strength gate FREEZE while fleets are committed:
		// a faction cannot join (or slip out of) a coalition whose enforcement
		// action is already underway - the roster was fixed when the fleets
		// sailed. Recomputation resumes once the strike resolves.
		if (intel.isStrikeActive() || intel.getSupportingStrikeFor() != null) return;

		if (!CommWarsConfig.gateEnabled()) {
			intel.setGateState(true, new ArrayList<String>());
			return;
		}

		float threshold = CoalitionCalc.gateThreshold();
		float own = MilitaryScore.factionScore(intel.getFaction());

		if (own >= threshold) {
			// strong enough alone: no coalition needed
			intel.setGateState(true, new ArrayList<String>());
			return;
		}

		List<String> partners = CoalitionCalc.partnersFor(intel.getFactionId(), activeIds);
		float pooled = CoalitionCalc.pooledScore(intel.getFactionId(), partners);
		boolean emboldened = pooled >= threshold;
		// one coalition anchor at a time, sector-wide: only the strongest
		// too-weak faction fronts a bloc - everyone else stays gated and backs
		// it where eligible, instead of each declaring an overlapping bloc
		// borrowed from the same strong factions
		if (emboldened && !intel.getFactionId().equals(tickBlocAnchor)) {
			emboldened = false;
		}
		// keep the attempted partner list either way, for display/debug
		intel.setGateState(emboldened, partners);

		if (!emboldened && intel.getProgress() > GrievanceEventIntel.GATE_CAP) {
			intel.setProgress(GrievanceEventIntel.GATE_CAP);
		}
	}

	/** Resolve a finished enforcement strike into consequences. */
	protected void pollStrike(GrievanceEventIntel intel) {
		com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI strike = intel.getStrike();
		if (strike == null) return;
		if (CommWarsConfig.debugLogging()) {
			String sf = strike.getRaidAction() != null
					? "" + strike.getRaidAction().getSuccessFraction() : "n/a";
			CommWarsConfig.log("strike poll " + intel.getFactionId()
					+ ": successFraction=" + sf
					+ " aborted=" + strike.isAborted()
					+ " failed=" + strike.isFailed()
					+ " succeeded=" + strike.isSucceeded()
					+ " ended=" + strike.isEnded());
		}
		// convert any raids still flying pre-adaptive locked bombardment orders
		intel.retrofitAdaptiveRaids();
		intel.checkStrikeRaidHit();
		// a coalition strike is several raids: judge the operation as a WHOLE.
		// One contingent being wiped while others still sail is not an outcome -
		// wait until every raid has run its course, then: any success = the
		// strike succeeded; all beaten = a true joint defeat.
		if (!intel.allStrikeRaidsResolved()) return;
		if (intel.anyStrikeRaidSucceeded()) {
			intel.onStrikeSucceeded();
		} else if (strike.isAborted() || strike.isFailed() || strike.isEnded()) {
			intel.onStrikeDefeated();
		} else {
			// anchor ended some other way with no contingent succeeding
			intel.clearStrike();
		}
	}

	/**
	 * The enforcement stage is a one-off in the event framework; re-arm it
	 * once the previous strike has resolved so it can fire again.
	 */
	protected void rearmEnforcement(GrievanceEventIntel intel) {
		GrievanceEventIntel.EventStageData enf =
				intel.getDataFor(GrievanceEventIntel.Stage.ENFORCEMENT);
		if (enf != null && enf.wasEverReached && !intel.isStrikeActive()
				&& intel.getProgress() < GrievanceEventIntel.ENFORCEMENT_PROGRESS
				&& intel.daysSinceLastStrikeEnd() >= CommWarsConfig.strikeCooldownDays()) {
			enf.wasEverReached = false;
		}
	}

	/**
	 * Does the player hold a permanent pather agreement that should pacify this
	 * faction? True only for the Luddic Path, and only while the vanilla
	 * "$patherAgreementPermanent" flag is set - the same flag the vanilla
	 * questline and the Remnant Retribution planetkiller handover both set.
	 * Reads vanilla player memory directly, so it works with no dependency on
	 * how the agreement was earned.
	 */
	protected boolean isPatherPeaceActive(String factionId) {
		if (!CommWarsConfig.patherPeace()) return false;
		if (!com.fs.starfarer.api.impl.campaign.ids.Factions.LUDDIC_PATH.equals(factionId)) {
			return false;
		}
		return com.fs.starfarer.api.impl.campaign.rulecmd.HA_CMD
				.playerPatherAgreementIsPermanent();
	}

	protected Map<String, GrievanceEventIntel> getActiveGrievances() {
		Map<String, GrievanceEventIntel> result = new LinkedHashMap<String, GrievanceEventIntel>();
		for (IntelInfoPlugin p : Global.getSector().getIntelManager().getIntel(GrievanceEventIntel.class)) {
			GrievanceEventIntel intel = (GrievanceEventIntel) p;
			if (intel.isEnding() || intel.isEnded()) continue;
			result.put(intel.getFactionId(), intel);
		}
		return result;
	}

	protected void handleDebugToggles(Map<String, List<ShareTracker.Cause>> all,
									  Map<String, GrievanceEventIntel> active) {
		// force a grievance open even without meeting the threshold - great
		// for seeing the intel screen without an export empire
		if (CommWarsConfig.debugForceGrievance() && active.isEmpty()) {
			String factionId = null;
			if (!all.isEmpty()) {
				// pick the angriest candidate
				float best = -1f;
				for (Map.Entry<String, List<ShareTracker.Cause>> e : all.entrySet()) {
					float total = ShareTracker.totalWeight(e.getValue());
					if (total > best) {
						best = total;
						factionId = e.getKey();
					}
				}
			} else {
				// no candidates at all: pick the faction with the most markets
				int best = 0;
				for (FactionAPI faction : Global.getSector().getAllFactions()) {
					if (faction.isPlayerFaction()) continue;
					int count = Misc.getFactionMarkets(faction, null).size();
					if (count > best) {
						best = count;
						factionId = faction.getId();
					}
				}
			}
			if (factionId != null) {
				GrievanceEventIntel intel = new GrievanceEventIntel(factionId, all.get(factionId));
				active.put(factionId, intel);
				CommWarsConfig.log("DEBUG: force-opened grievance with " + factionId);
			}
		}

		// jump the angriest grievance to the ultimatum stage
		if (CommWarsConfig.debugForceUltimatum() && !active.isEmpty()) {
			GrievanceEventIntel angriest = null;
			for (GrievanceEventIntel intel : active.values()) {
				if (angriest == null || intel.getProgress() > angriest.getProgress()) {
					angriest = intel;
				}
			}
			if (angriest != null && angriest.getProgress() < GrievanceEventIntel.ULTIMATUM_PROGRESS) {
				CommWarsConfig.log("DEBUG: forcing " + angriest.getFactionId() + " to ULTIMATUM");
				angriest.setProgress(GrievanceEventIntel.ULTIMATUM_PROGRESS);
			}
		}

		// push the angriest strike-free grievance to enforcement
		if (CommWarsConfig.debugForceStrike() && !active.isEmpty()) {
			GrievanceEventIntel angriest = null;
			for (GrievanceEventIntel intel : active.values()) {
				if (intel.isStrikeActive()) continue;
				if (angriest == null || intel.getProgress() > angriest.getProgress()) {
					angriest = intel;
				}
			}
			if (angriest != null && !angriest.isStrikeActive()) {
				CommWarsConfig.log("DEBUG: forcing " + angriest.getFactionId() + " to ENFORCEMENT");
				// deliberate bypass of the strike cooldown: force means force
				GrievanceEventIntel.EventStageData enf =
						angriest.getDataFor(GrievanceEventIntel.Stage.ENFORCEMENT);
				if (enf != null) enf.wasEverReached = false;
				angriest.setProgress(GrievanceEventIntel.ENFORCEMENT_PROGRESS);
			}
		}
	}

	protected void logTickSummary(Map<String, Float> totals,
								  Map<String, GrievanceEventIntel> active) {
		StringBuilder sb = new StringBuilder();
		sb.append("tick: ").append(totals.size()).append(" aggrieved faction(s), ")
				.append(active.size()).append(" active grievance(s)");
		for (Map.Entry<String, Float> e : totals.entrySet()) {
			sb.append(" | ").append(e.getKey()).append("=").append(e.getValue());
		}
		CommWarsConfig.log(sb.toString());
	}
}
