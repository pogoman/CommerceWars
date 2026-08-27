package commwars;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.impl.campaign.command.WarSimScript;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MutableCommodityQuantity;
import com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction.FGRaidType;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI.GenericRaidParams;
import com.fs.starfarer.api.impl.campaign.missions.FleetCreatorMission.FleetStyle;
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionWithTriggers.ComplicationRepImpact;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.BombardType;
import com.fs.starfarer.api.util.Misc;

/**
 * Builds and launches an enforcement strike for a grievance: a raid fleet
 * group from the aggrieved faction targeting the player market that produces
 * the most of the contested commodities. Low escalation sends disruption
 * raids against the offending industries; at the configured escalation level
 * strikes switch to tactical bombardment.
 */
public class EnforcementStrike {

	/** Player market producing the most of the contested commodities. */
	public static MarketAPI findTarget(List<String> contested) {
		MarketAPI best = null;
		float bestScore = -1f;
		for (MarketAPI market : Misc.getPlayerMarkets(false)) {
			if (market.getStarSystem() == null) continue;
			float score = 0f;
			for (Industry ind : market.getIndustries()) {
				for (MutableCommodityQuantity q : ind.getAllSupply()) {
					if (contested.contains(q.getCommodityId())) {
						score += q.getQuantity().getModifiedValue();
					}
				}
			}
			// fallback ordering when nothing is produced anywhere: market size
			score = score * 1000f + market.getSize();
			if (score > bestScore) {
				bestScore = score;
				best = market;
			}
		}
		return best;
	}

	/** Industries on the target that supply any contested commodity. */
	public static List<String> findOffendingIndustries(MarketAPI target, List<String> contested) {
		Set<String> result = new LinkedHashSet<String>();
		for (Industry ind : target.getIndustries()) {
			for (MutableCommodityQuantity q : ind.getAllSupply()) {
				if (contested.contains(q.getCommodityId())
						&& q.getQuantity().getModifiedValue() > 0) {
					result.add(ind.getId());
					break;
				}
			}
		}
		return new ArrayList<String>(result);
	}

	/** Player market with the most military infrastructure - the military-track target. */
	public static MarketAPI findMilitaryTarget() {
		MarketAPI best = null;
		float bestScore = -1f;
		for (MarketAPI market : Misc.getPlayerMarkets(false)) {
			if (market.getStarSystem() == null) continue;
			float score = MilitaryScore.marketScore(market);
			if (score > bestScore) {
				bestScore = score;
				best = market;
			}
		}
		return best;
	}

	/** Military-relevant industries on the target - what a military strike disrupts. */
	public static List<String> findMilitaryIndustries(MarketAPI target) {
		List<String> result = new ArrayList<String>();
		for (Industry ind : target.getIndustries()) {
			if (MilitaryScore.industryWeight(ind.getId()) > 0) {
				result.add(ind.getId());
			}
		}
		return result;
	}

	/** A planned item heist: which item to go for, and the marine math behind it. */
	public static class HeistPlan {
		public MarketAPI market;
		public String industryId;
		public float neededMarines;
		public float capacity;
	}

	/**
	 * Look for an installed item worth a ground operation. The heist only
	 * happens if the faction can actually muster enough marines - from its
	 * real marine production - to overmatch the target's ground defenses.
	 */
	public static HeistPlan planHeist(GrievanceEventIntel intel, boolean militaryMode,
									  List<String> contested) {
		float capacity = MilitaryScore.marineCapacity(intel.getFaction());

		List<MarketAPI> markets = new ArrayList<MarketAPI>(Misc.getPlayerMarkets(false));
		java.util.Collections.sort(markets, new java.util.Comparator<MarketAPI>() {
			@Override
			public int compare(MarketAPI a, MarketAPI b) {
				return b.getSize() - a.getSize();
			}
		});

		for (MarketAPI market : markets) {
			if (market.getStarSystem() == null) continue;
			List<String> candidates = militaryMode
					? findMilitaryIndustries(market)
					: findOffendingIndustries(market, contested);
			for (String industryId : candidates) {
				Industry ind = market.getIndustry(industryId);
				if (ind == null || ind.getSpecialItem() == null) continue;

				float needed = MarketCMD.getDefenderStr(market) * CommWarsConfig.heistOvermatch();
				if (capacity < needed) {
					CommWarsConfig.log("Heist by " + intel.getFactionId() + " vs "
							+ market.getName() + " (" + industryId + "): needs "
							+ (int) needed + " marines, can muster " + (int) capacity
							+ " - insufficient");
					continue;
				}
				HeistPlan plan = new HeistPlan();
				plan.market = market;
				plan.industryId = industryId;
				plan.neededMarines = needed;
				plan.capacity = capacity;
				return plan;
			}
		}
		return null;
	}

	/** Mustering the operation visibly drains marines at their producing markets. */
	public static void applyMusterMalus(FactionAPI faction, float neededMarines, float capacity) {
		// The committed marines are not consumed - survivors return. Only
		// CASUALTIES are a lasting drain, recovering as replacements are
		// trained. Casualty rate falls the more overwhelming the force is
		// (overmatch above the minimum reduces losses), so a lopsided heist
		// against a soft target barely dents the corps, while one that forced
		// them to scrape together every marine takes heavier proportional
		// losses.
		float overmatch = Math.max(1f, CommWarsConfig.heistOvermatch());
		float casualtyRate = CommWarsConfig.musterCasualtyFraction() / overmatch;
		float casualties = neededMarines * casualtyRate;

		float totalProduction = 0f;
		for (MarketAPI market : Misc.getFactionMarkets(faction, null)) {
			totalProduction += MilitaryScore.marineProduction(market);
		}
		float casualtySupplyUnits = casualties / Math.max(1f, CommWarsConfig.marinesPerSupply());

		for (MarketAPI market : Misc.getFactionMarkets(faction, null)) {
			float production = MilitaryScore.marineProduction(market);
			if (production <= 0) continue;
			float share = totalProduction > 0 ? production / totalProduction : 1f;
			int penalty = Math.max(CommWarsConfig.heistMusterPenalty(),
					Math.round(casualtySupplyUnits * share));
			try {
				market.getCommodityData(com.fs.starfarer.api.impl.campaign.ids.Commodities.MARINES)
						.getAvailableStat().addTemporaryModFlat(CommWarsConfig.heistMusterDays(),
								"commwars_muster", "Marine losses from the raid", -penalty);
			} catch (Throwable t) {
				CommWarsConfig.log("muster malus failed at " + market.getName() + ": " + t);
			}
		}
		CommWarsConfig.log("Marine casualties: " + (int) casualties + " of " + (int) neededMarines
				+ " committed (rate " + Math.round(casualtyRate * 100) + "%), recovering over "
				+ (int) CommWarsConfig.heistMusterDays() + " days");
	}

	/** Largest market of the faction that can actually mount the strike. */
	public static MarketAPI findSource(FactionAPI faction) {
		MarketAPI best = null;
		for (MarketAPI market : Misc.getFactionMarkets(faction, null)) {
			if (market.getPrimaryEntity() == null) continue;
			if (market.getStarSystem() == null) continue;
			if (best == null || market.getSize() > best.getSize()) {
				best = market;
			}
		}
		return best;
	}

	/**
	 * Launches the strike and registers it on the intel. Returns false if no
	 * valid source or target exists (e.g. the player has no colonies).
	 */
	public static boolean launch(GrievanceEventIntel intel) {
		List<String> contested = intel.getCauseCommodityIds();
		boolean militaryMode = intel.isMilitaryDominant();
		boolean vendetta = intel.isVendetta();

		// at high escalation, look for an installed item worth a ground op -
		// but a blood feud wants annihilation, not loot
		HeistPlan heist = null;
		if (!vendetta && intel.getEscalation() >= CommWarsConfig.heistEscalation()) {
			heist = planHeist(intel, militaryMode, contested);
		}

		MarketAPI target = heist != null ? heist.market
				: vendetta ? findTarget(new ArrayList<String>()) // largest player colony
				: militaryMode ? findMilitaryTarget() : findTarget(contested);
		MarketAPI source = findSource(intel.getFaction());
		if (target == null || source == null) {
			CommWarsConfig.log("Enforcement strike by " + intel.getFactionId()
					+ " aborted: no valid " + (target == null ? "target" : "source"));
			return false;
		}

		Random random = new Random();
		GenericRaidParams params = new GenericRaidParams(new Random(random.nextLong()), true);

		params.factionId = intel.getFactionId();
		params.source = source;
		params.prepDays = 14f + 7f * random.nextFloat();
		// short payload window: the FGI only counts as succeeded once this
		// window closes, and theft/truce consequences fire then - keep the
		// gap between the visible raid and its consequences tight
		params.payloadDays = 21f + 9f * random.nextFloat();
		params.makeFleetsHostile = true;

		params.raidParams.where = target.getStarSystem();
		params.raidParams.type = FGRaidType.CONCURRENT;
		params.raidParams.tryToCaptureObjectives = false;
		params.raidParams.allowedTargets.add(target);
		params.raidParams.allowNonHostileTargets = true;
		params.raidParams.doNotGetSidetracked = true;

		boolean tacBomb = heist == null && !vendetta
				&& intel.getEscalation() >= CommWarsConfig.tacBombEscalation();
		if (vendetta) {
			// the blood feud answers saturation with saturation
			params.raidParams.setBombardment(BombardType.SATURATION);
		} else if (heist != null) {
			// a ground operation: breach the fortifications, take the item
			List<String> disrupt = new ArrayList<String>();
			if (target.getIndustry(Industries.GROUNDDEFENSES) != null) {
				disrupt.add(Industries.GROUNDDEFENSES);
			}
			if (target.getIndustry(Industries.HEAVYBATTERIES) != null) {
				disrupt.add(Industries.HEAVYBATTERIES);
			}
			disrupt.add(heist.industryId);
			params.raidParams.setDisrupt(disrupt.toArray(new String[0]));
			applyMusterMalus(intel.getFaction(), heist.neededMarines, heist.capacity);
		} else if (tacBomb) {
			params.raidParams.setBombardment(BombardType.TACTICAL);
		} else {
			List<String> industries = militaryMode
					? findMilitaryIndustries(target)
					: findOffendingIndustries(target, contested);
			if (!industries.isEmpty()) {
				params.raidParams.setDisrupt(industries.toArray(new String[0]));
			}
		}

		params.noun = vendetta ? "retribution" : "enforcement action";
		params.forcesNoun = vendetta ? "retribution forces" : "enforcement forces";
		params.style = FleetStyle.STANDARD;
		params.repImpact = ComplicationRepImpact.NONE;

		// --- grounded fleet scaling ---
		// enough to ensure victory over the target's actual defenses...
		FactionAPI player = Global.getSector().getPlayerFaction();
		float targetStr = WarSimScript.getFactionStrength(player, target.getStarSystem())
				+ WarSimScript.getStationStrength(player, target.getStarSystem(),
						target.getPrimaryEntity());
		float needed = targetStr / CommWarsConfig.strengthPerDifficulty()
				* CommWarsConfig.overmatch();

		// ...escalated by a history of defiance...
		float desired = Math.max(CommWarsConfig.strikeBaseDifficulty(), needed)
				* (1f + intel.getEscalation() * CommWarsConfig.strikeEscalationMult());

		// ...but hard-capped by what their military-industrial base can field -
		// a coalition pools every member's capacity
		float capacityScore = MilitaryScore.factionScore(intel.getFaction());
		for (String partner : intel.getCoalitionPartners()) {
			FactionAPI partnerFaction = Global.getSector().getFaction(partner);
			if (partnerFaction != null) {
				capacityScore += MilitaryScore.factionScore(partnerFaction);
			}
		}
		float capacity = capacityScore * CommWarsConfig.capacityMult();

		float difficulty = Math.min(desired, Math.min(capacity, CommWarsConfig.strikeMaxDifficulty()));
		if (difficulty < 15f) difficulty = 15f; // token force floor: they always send *something*

		intel.setLastStrikeMath("targetStr " + (int) targetStr
				+ " -> needed " + (int) needed
				+ " | desired " + (int) desired + " (escalation " + intel.getEscalation() + ")"
				+ " | capacity " + (int) capacity
				+ " | final " + (int) difficulty);
		CommWarsConfig.log("Strike scaling: " + intel.getLastStrikeMath());

		difficulty -= 10;
		params.fleetSizes.add(10);
		while (difficulty > 0) {
			int size = 6 + random.nextInt(5);
			params.fleetSizes.add(size);
			difficulty -= size;
		}

		GenericRaidFGI raid;
		if (!intel.getCoalitionPartners().isEmpty()) {
			List<String> members = new ArrayList<String>();
			members.add(intel.getFactionId());
			members.addAll(intel.getCoalitionPartners());
			raid = new CoalitionRaidFGI(params, members);
		} else {
			raid = new GenericRaidFGI(params);
		}
		Global.getSector().getIntelManager().addIntel(raid);

		intel.setStrike(raid, target, source, tacBomb || vendetta,
				militaryMode || vendetta, heist != null ? heist.industryId : null);

		// the joint action speaks for the whole coalition: every member's
		// ledger vents into it - reset and held until the strike resolves
		for (String partnerId : intel.getCoalitionPartners()) {
			GrievanceEventIntel partner = GrievanceEventIntel.get(partnerId);
			if (partner != null) {
				partner.joinCoalitionStrike(intel.getFactionId());
			}
		}

		CommWarsConfig.log("Enforcement strike launched: " + intel.getFactionId()
				+ " vs " + target.getName() + " (escalation " + intel.getEscalation()
				+ ", " + (vendetta ? "VENDETTA, " : militaryMode ? "MILITARY track, " : "trade track, ")
				+ (vendetta ? "SATURATION BOMBARDMENT"
					: heist != null
					? "ITEM HEIST vs " + heist.industryId + " (marines "
							+ (int) heist.capacity + "/" + (int) heist.neededMarines + ")"
					: tacBomb ? "TACTICAL BOMBARDMENT" : "disruption raid") + ")");
		return true;
	}
}
