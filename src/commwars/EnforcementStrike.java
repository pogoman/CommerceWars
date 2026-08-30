package commwars;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

	/**
	 * Rough war-fleet pace for convergence timing: ~burn 8, half a light-year
	 * per day per burn point. Only used to stagger departures so coalition
	 * contingents arrive together - precision doesn't matter, spread does.
	 */
	public static final float LY_PER_DAY = 4f;

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

	/** Total production of the given commodities at a market (0 = makes none of them). */
	public static float contestedProduction(MarketAPI market, List<String> contested) {
		float score = 0f;
		for (Industry ind : market.getIndustries()) {
			for (MutableCommodityQuantity q : ind.getAllSupply()) {
				if (contested.contains(q.getCommodityId())) {
					score += q.getQuantity().getModifiedValue();
				}
			}
		}
		return score;
	}

	/**
	 * An industry a raid can meaningfully disrupt: vanilla raid duration is
	 * marine tokens x the industry's disrupt-danger days, so a zero-danger
	 * industry (Population & Infrastructure above all) clamps to a 2-day blip
	 * - a wasted raid action that reads as "nothing happened" to the player.
	 */
	protected static boolean meaningfullyDisruptable(Industry ind) {
		return ind.getSpec() != null && ind.getSpec().getDisruptDanger() != null
				&& ind.getSpec().getDisruptDanger().disruptionDays > 0;
	}

	/** Industries on the target that supply any contested commodity. */
	public static List<String> findOffendingIndustries(MarketAPI target, List<String> contested) {
		Set<String> result = new LinkedHashSet<String>();
		for (Industry ind : target.getIndustries()) {
			if (!meaningfullyDisruptable(ind)) continue;
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
			if (!meaningfullyDisruptable(ind)) continue;
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
			List<CampaignFleetAPI> fleets, MarketAPI target, boolean isHeist,
			float projectedRaidStr, float opad) {
		if (target == null) return;
		Color h = Misc.getHighlightColor();
		Color good = Misc.getPositiveHighlightColor();
		Color bad = Misc.getNegativeHighlightColor();

		float defenderStr = MarketCMD.getDefenderStr(target);

		// prefer the live, measured ground strength once their fleets are close
		// enough to scout; until then (staging and most of the approach) their
		// fleets are not spawned, so fall back to the force they committed at
		// launch - the marines are loaded when they leave, ~50 days before they
		// arrive, so this is what is actually inbound.
		float raidStr = 0f;
		if (fleets != null) {
			for (CampaignFleetAPI fleet : fleets) raidStr += MarketCMD.getRaidStr(fleet);
		}
		boolean measured = raidStr > 0f;
		if (!measured) raidStr = projectedRaidStr;
		if (raidStr <= 0f) return; // no committed force known yet either

		float margin = isHeist
				? CommWarsConfig.heistBreakMargin() : CommWarsConfig.groundBreakMargin();
		boolean willBreak = raidStr >= defenderStr * margin;
		float ratio = defenderStr > 0 ? raidStr / defenderStr : 99f;

		info.addPara("Ground assessment: their " + (measured ? "raid" : "projected raid")
				+ " strength %s against " + target.getName()
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

		if (!measured) {
			info.addPara("This is the force they committed at departure, weighed against your "
					+ "defenses as they stand now. Raise your ground defenses before they arrive "
					+ "to shift the outcome.", 3f, Misc.getGrayColor());
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
	 * valid target exists, or no member can stage a raid (e.g. the player has
	 * no colonies).
	 *
	 * A coalition strikes as SEVERAL simultaneous raids - one per member, each
	 * under its own flag, each its own intel marker - so a joint enforcement
	 * plainly reads as one: Tri-Tachyon, Hegemony, and independent fleets
	 * converging on the target alongside the anchor. The total strength budget
	 * is split across them, so it is the same force as a single pooled raid,
	 * just flown under the colors of everyone actually pressing the grievance.
	 * The anchor's own raid is the one the grievance tracks for consequences
	 * (ground assault, disruption, truce, escalation); the partners' raids do
	 * their own vanilla damage and withdraw. A heist or a blood feud is one
	 * faction's operation and never splits.
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
		if (target == null) {
			CommWarsConfig.log("Enforcement strike by " + intel.getFactionId()
					+ " aborted: no valid target");
			return false;
		}

		// bombardment authority for the adaptive contingents; the heist raid
		// itself never bombs (a scripted ground op to lift the asset intact)
		boolean tacBomb = !vendetta
				&& intel.getEscalation() >= CommWarsConfig.tacBombEscalation();

		// --- grounded strength budget ---
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

		// a coalition splits into one raid per member. A heist is still a joint
		// operation: the ANCHOR storms the vault with an oversized contingent
		// while the partners open their own fronts - diversion and smash-and-
		// grab. Only a vendetta is one faction's business alone.
		boolean coalition = !vendetta && !intel.getCoalitionPartners().isEmpty();
		List<String> members = new ArrayList<String>();
		members.add(intel.getFactionId());
		if (coalition) members.addAll(intel.getCoalitionPartners());

		Random random = new Random();
		// weighted split of the total: the vault-storming anchor of a heist
		// gets heistForceMult shares, everyone else one share each - solo
		// strikes collapse to the full budget either way
		float anchorWeight = heist != null ? CommWarsConfig.heistForceMult() : 1f;
		float totalWeight = anchorWeight + (members.size() - 1);
		float share = difficulty / totalWeight;
		float anchorBudget = share * anchorWeight;

		// --- per-member targets: each contingent chases ITS OWN grievance ---
		// The colony producing the most of the member's contested goods is its
		// mark - if the player's production is spread out, the coalition fans
		// out across colonies and cannot be met everywhere at once; if one
		// colony really is the hub, they converge on it. A member with no real
		// producer of its own goods (score 0) sails with the anchor instead -
		// no battle groups solemnly raiding a size-3 backwater.
		Map<String, MarketAPI> targets = new java.util.LinkedHashMap<String, MarketAPI>();
		for (String memberId : members) {
			MarketAPI memberTarget = target; // the anchor's colony by default
			if (coalition && !memberId.equals(intel.getFactionId())) {
				GrievanceEventIntel memberIntel = GrievanceEventIntel.get(memberId);
				if (memberIntel != null) {
					if (memberIntel.isMilitaryDominant()) {
						MarketAPI own = findMilitaryTarget();
						if (own != null && MilitaryScore.marketScore(own) > 0) {
							memberTarget = own;
						}
					} else {
						List<String> ownContested = memberIntel.getCauseCommodityIds();
						MarketAPI own = findTarget(ownContested);
						if (own != null && contestedProduction(own, ownContested) > 0) {
							memberTarget = own;
						}
					}
				}
			}
			targets.put(memberId, memberTarget);
		}

		// --- synchronized convergence ---
		// A coalition that trickles in over weeks is defeated in detail by a
		// player fighting one contingent at a time. Estimate each member's
		// travel time from its staging market to ITS target and pad the
		// NEARER members' prep so every front opens within days of the others.
		Map<String, MarketAPI> sources = new java.util.LinkedHashMap<String, MarketAPI>();
		Map<String, Float> travelDays = new java.util.LinkedHashMap<String, Float>();
		float maxTravelDays = 0f;
		for (String memberId : members) {
			FactionAPI memberFaction = Global.getSector().getFaction(memberId);
			if (memberFaction == null) continue;
			MarketAPI source = findSource(memberFaction);
			if (source == null) continue;
			sources.put(memberId, source);
			float dist = Misc.getDistanceLY(source.getPrimaryEntity(),
					targets.get(memberId).getPrimaryEntity());
			float days = dist / LY_PER_DAY;
			travelDays.put(memberId, days);
			if (days > maxTravelDays) maxTravelDays = days;
		}
		float basePrep = 14f + 7f * random.nextFloat();
		// a PLANNING window before any fleet musters, same length for every
		// contingent (arrivals stay synchronized): vanilla's sabotage
		// counterplay lives here - disrupt the staging market's military base
		// or high command (bombardment, raid) while the operation is in the
		// planning stages, and that contingent's strike is aborted outright
		float planningDays = 10f + 5f * random.nextFloat();

		GenericRaidFGI anchorRaid = null;
		MarketAPI anchorSource = null;
		List<GenericRaidFGI> partnerRaids = new ArrayList<GenericRaidFGI>();
		int launched = 0;

		for (String memberId : members) {
			MarketAPI source = sources.get(memberId);
			if (source == null) {
				CommWarsConfig.log("  " + memberId + " cannot join the strike: no staging market");
				continue;
			}

			boolean isAnchor = memberId.equals(intel.getFactionId());
			MarketAPI memberTarget = targets.get(memberId);
			// the heist ground op belongs to the anchor alone; partner
			// contingents fly ordinary adaptive enforcement raids alongside
			HeistPlan memberHeist = isAnchor ? heist : null;

			// each contingent goes after the industries behind ITS OWN grievance
			// at ITS OWN target: the ore-aggrieved faction hits the mining hub,
			// the fuel-aggrieved one the fuel works - falling back to the
			// anchor's list if its target makes nothing this member contests
			GrievanceEventIntel memberIntel = isAnchor ? intel
					: GrievanceEventIntel.get(memberId);
			List<String> disruptIndustries = memberDisruptIndustries(
					memberIntel, memberTarget, contested, militaryMode);

			GenericRaidParams params = buildRaidParams(memberId, source, memberTarget,
					disruptIndustries, vendetta, memberHeist, random);
			// nearer contingents wait in port so every front opens together
			params.prepDays = basePrep + (maxTravelDays - travelDays.get(memberId));
			float projectedRaidStr = buildFleetSizes(params,
					isAnchor ? anchorBudget : share, random);

			// tacBomb no longer locks the payload: it AUTHORIZES bombardment as
			// the fallback when a contingent finds its industries already silent
			// (never for the heist raid itself - it wants the asset intact)
			GenericRaidFGI raid = new EnforcementRaidFGI(params, memberHeist != null,
					projectedRaidStr, tacBomb && memberHeist == null);
			// open the vanilla planning window: while it lasts, knocking out
			// the staging market's military base/high command aborts this
			// contingent (the intel status shows the hint, as for expeditions)
			raid.setPreFleetDeploymentDelay(planningDays);
			// the anchor's raid pings as usual; partner contingents are added
			// silently (the grievance already announces the joint action) so a
			// coalition launch doesn't spam a notification per faction
			Global.getSector().getIntelManager().addIntel(raid, !isAnchor);
			launched++;
			CommWarsConfig.log("  raid: " + memberId + " vs " + memberTarget.getName()
					+ " (budget " + (int) (isAnchor ? anchorBudget : share)
					+ (memberHeist != null ? " HEIST" : "")
					+ ", " + params.fleetSizes.size() + " fleets"
					+ ", planning " + (int) planningDays + "d (sabotage window) + prep "
					+ (int) params.prepDays + "d + travel ~"
					+ (int) travelDays.get(memberId).floatValue() + "d"
					+ (disruptIndustries.isEmpty() ? "" : ", hits " + disruptIndustries) + ")");

			if (isAnchor) {
				anchorRaid = raid;
				anchorSource = source;
			} else {
				partnerRaids.add(raid);
				// the joint action speaks for this partner's grievance too: its
				// ledger vents into the strike - reset and held until resolution
				GrievanceEventIntel partner = GrievanceEventIntel.get(memberId);
				if (partner != null) partner.joinCoalitionStrike(intel.getFactionId());
			}
		}

		if (anchorRaid == null) {
			CommWarsConfig.log("Enforcement strike by " + intel.getFactionId()
					+ " aborted: no staging market for the leading faction");
			return false;
		}

		// the grievance tracks the anchor's own raid for consequences; the
		// partners' contingents run alongside it and count toward the combined
		// ground assault (setStrike resets the support list, so register after).
		// Only a vendetta is a committed bombardment now - enforcement strikes
		// decide at the raid whether shells are actually needed.
		intel.setStrike(anchorRaid, target, anchorSource, vendetta,
				militaryMode || vendetta, heist != null ? heist.industryId : null);
		for (GenericRaidFGI partnerRaid : partnerRaids) {
			intel.addSupportRaid(partnerRaid);
		}

		CommWarsConfig.log("Enforcement strike launched: " + intel.getFactionId()
				+ (coalition ? " with coalition (" + launched + " raids)" : "")
				+ " vs " + target.getName() + " (escalation " + intel.getEscalation()
				+ ", " + (vendetta ? "VENDETTA, " : militaryMode ? "MILITARY track, " : "trade track, ")
				+ (vendetta ? "SATURATION BOMBARDMENT"
					: heist != null
					? "ITEM HEIST attempt vs " + heist.industryId
							+ " (needs overwhelming ground advantage)"
					: tacBomb ? "adaptive raid, bombardment authorized"
					: "adaptive disruption raid") + ")");
		return true;
	}

	/**
	 * The industries this coalition member goes after: the ones on the target
	 * producing ITS OWN contested commodities (or, if it is a military-track
	 * grievance, the target's military industries). Falls back to the leading
	 * faction's contested industries when the target makes nothing this member
	 * itself contests, so the contingent still strikes something relevant.
	 */
	public static List<String> memberDisruptIndustries(GrievanceEventIntel memberIntel,
			MarketAPI target, List<String> anchorContested, boolean anchorMilitary) {
		boolean military = memberIntel != null ? memberIntel.isMilitaryDominant() : anchorMilitary;
		if (military) {
			return findMilitaryIndustries(target);
		}
		List<String> contested = memberIntel != null
				? memberIntel.getCauseCommodityIds() : anchorContested;
		List<String> industries = findOffendingIndustries(target, contested);
		if (industries.isEmpty() && contested != anchorContested) {
			industries = findOffendingIndustries(target, anchorContested);
		}
		return industries;
	}

	/**
	 * Build the raid parameters for one faction's contingent: staging market,
	 * timing, target, and payload, all under that faction's own flag. Only a
	 * vendetta (saturation) or a heist locks its payload in here; a plain
	 * enforcement raid carries its industry hit-list and decides in real time,
	 * fleet by fleet, whether to disrupt, bombard, or raid generically
	 * (see {@link EnforcementRaidFGI#doCustomRaidAction}).
	 */
	private static GenericRaidParams buildRaidParams(String factionId, MarketAPI source,
			MarketAPI target, List<String> disruptIndustries, boolean vendetta,
			HeistPlan heist, Random random) {
		GenericRaidParams params = new GenericRaidParams(new Random(random.nextLong()), true);

		params.factionId = factionId;
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
		} else {
			// no locked-in bombardment: carry the hit-list, adapt at raid time
			if (disruptIndustries != null && !disruptIndustries.isEmpty()) {
				params.raidParams.setDisrupt(disruptIndustries.toArray(new String[0]));
			}
		}

		params.noun = vendetta ? "retribution" : "enforcement action";
		params.forcesNoun = vendetta ? "retribution forces" : "enforcement forces";
		params.style = FleetStyle.STANDARD;
		params.repImpact = ComplicationRepImpact.NONE;
		return params;
	}

	/**
	 * Spend a strength budget on a few substantial fleets rather than a swarm
	 * of tiny ones (same total strength, a believable handful of fleets, and -
	 * for a coalition member's share - enough size that each faction's raid is
	 * a real force). Returns the projected ground raid strength of the whole
	 * contingent, for the pre-arrival assessment.
	 */
	private static float buildFleetSizes(GenericRaidParams params, float budget, Random random) {
		params.fleetSizes.add(16);
		budget -= 16;
		while (budget > 0) {
			int size = 14 + random.nextInt(9); // 14-22 fleet points each
			params.fleetSizes.add(size);
			budget -= size;
		}
		int committedPoints = 0;
		for (int s : params.fleetSizes) committedPoints += s;
		return committedPoints * CommWarsConfig.groundStrPerFleetPoint();
	}
}
