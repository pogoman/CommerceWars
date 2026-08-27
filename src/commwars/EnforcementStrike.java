package commwars;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
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
	}

	/**
	 * Ground-side assessment for an inbound enforcement raid, shown on both
	 * the enforcement-action intel and the grievance screen. Vanilla's own
	 * assessment judges only the SPACE fight ("N fleets, defenders outmatched,
	 * likely success") and can read "success" while a shielded, fortified
	 * colony repels the landing. This shows the ground truth: the raiders'
	 * strength (once their fleets are close enough to measure) against the
	 * target's CURRENT defender strength - shield and garrison marines
	 * included - so the player can judge whether to reinforce before arrival.
	 */
	public static void appendGroundAssessment(TooltipMakerAPI info,
			List<CampaignFleetAPI> fleets, MarketAPI target, boolean isHeist, float opad) {
		if (target == null) return;
		Color h = Misc.getHighlightColor();
		Color good = Misc.getPositiveHighlightColor();
		Color bad = Misc.getNegativeHighlightColor();

		float defenderStr = MarketCMD.getDefenderStr(target);

		float raidStr = 0f;
		if (fleets != null) {
			for (CampaignFleetAPI fleet : fleets) raidStr += MarketCMD.getRaidStr(fleet);
		}

		if (raidStr <= 0f) {
			// their fleets are not yet close enough to assess; show the number
			// the player actually controls and can act on
			info.addPara("Ground assessment: your defender strength at " + target.getName()
					+ " is %s (planetary shield and garrison marines included). The raiders' "
					+ "strength will resolve as they close on the colony - keep your defenses "
					+ "above it to repel them.", opad, h, Misc.getWithDGS((int) defenderStr));
			return;
		}

		float margin = isHeist
				? CommWarsConfig.heistBreakMargin() : CommWarsConfig.groundBreakMargin();
		boolean willBreak = raidStr >= defenderStr * margin;
		float ratio = defenderStr > 0 ? raidStr / defenderStr : 99f;

		info.addPara("Ground assessment: their raid strength %s against " + target.getName()
				+ "'s defender strength %s (planetary shield and garrison marines included) - "
				+ "a %s-to-1 ground ratio.", opad, h,
				Misc.getWithDGS((int) raidStr), Misc.getWithDGS((int) defenderStr),
				Misc.getRoundedValueMaxOneAfterDecimal(ratio));

		if (isHeist) {
			info.addPara(willBreak
					? "They have the overwhelming advantage needed to storm the vault and seize "
							+ "the installed asset."
					: "They lack the overwhelming advantage needed to seize an installed asset "
							+ "(they need " + margin + "-to-1) - your defenses should hold it.",
					3f, willBreak ? bad : good, willBreak ? "" : "should hold it");
		} else {
			info.addPara(willBreak
					? "They can break your ground defenses - expect industries disrupted if this "
							+ "lands."
					: "Your ground defenses should repel this - they cannot break through.",
					3f, willBreak ? bad : good, willBreak ? "" : "should repel this");
		}
	}

	/**
	 * Find a player market holding an installed item worth a ground operation.
	 * Whether the item can actually be lifted is decided at the raid itself,
	 * against the target's live defenses (see GrievanceEventIntel.brokeGround)
	 * - lifting a strategic asset takes an overwhelming ground advantage, just
	 * as it does when the player raids a nanoforge off a core world.
	 */
	public static HeistPlan planHeist(GrievanceEventIntel intel, boolean militaryMode,
									  List<String> contested) {
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
				HeistPlan plan = new HeistPlan();
				plan.market = market;
				plan.industryId = industryId;
				return plan;
			}
		}
		return null;
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

		// ...and, for an item heist, an overwhelming ground force: lifting a
		// strategic asset takes far more than a disruption raid (as it does
		// when the player pulls a nanoforge off a core world)
		if (heist != null) {
			desired *= CommWarsConfig.heistForceMult();
		}

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
			raid = new CoalitionRaidFGI(params, members, heist != null);
		} else {
			raid = new EnforcementRaidFGI(params, heist != null);
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
					? "ITEM HEIST attempt vs " + heist.industryId
							+ " (needs overwhelming ground advantage)"
					: tacBomb ? "TACTICAL BOMBARDMENT" : "disruption raid") + ")");
		return true;
	}
}
