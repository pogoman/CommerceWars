package commwars;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.BombardType;
import com.fs.starfarer.api.ui.TooltipMakerAPI;

/**
 * An enforcement raid whose intel entry, in addition to vanilla's space-only
 * assessment ("N fleets, defenders outmatched, likely success"), shows a
 * GROUND assessment: whether the raiders can actually break the target's
 * ground defenses (planetary shield and garrison marines included). The
 * vanilla "success" only means the fleets reach the planet - it says nothing
 * about whether a shielded, fortified colony will repel the landing. This is
 * re-rendered every view, so it reflects the player's current defenses.
 *
 * Enforcement raids also decide their payload IN REAL TIME, fleet by fleet,
 * against the target's state at the moment of the raid (via vanilla's
 * custom-raid-action hook) rather than locking an intent in at launch:
 *   1. disrupt the first of this contingent's grievance industries still
 *      OPERATING (skipping ones already knocked out - by an earlier fleet,
 *      a coalition partner's contingent, or a previous strike);
 *   2. if every relevant industry already lies silent and escalation permits,
 *      resort to tactical bombardment (once per contingent);
 *   3. failing both, a generic raid - stockpiles and stability.
 * Heist operations and vendetta saturation bombardments keep their fixed
 * payload; adapting is for enforcement, not annihilation.
 */
public class EnforcementRaidFGI extends GenericRaidFGI {

	protected boolean heist;
	protected float projectedRaidStr;
	protected boolean allowBombard;
	protected boolean didBombard = false;
	// what this contingent actually did, action by action - shown on the
	// intel entry so the after-action report reads like one
	protected java.util.List<String> operationsLog = new java.util.ArrayList<String>();

	public EnforcementRaidFGI(GenericRaidParams params, boolean heist, float projectedRaidStr) {
		this(params, heist, projectedRaidStr, false);
	}

	public EnforcementRaidFGI(GenericRaidParams params, boolean heist, float projectedRaidStr,
			boolean allowBombard) {
		super(params);
		this.heist = heist;
		this.projectedRaidStr = projectedRaidStr;
		this.allowBombard = allowBombard;
	}

	public float getProjectedRaidStr() {
		return projectedRaidStr;
	}

	/** Did this contingent actually resort to a bombardment during the raid? */
	public boolean isDidBombard() {
		return didBombard;
	}

	public MarketAPI getGroundTarget() {
		if (params.raidParams.allowedTargets.isEmpty()) return null;
		return params.raidParams.allowedTargets.get(0);
	}

	// ---- real-time payload choice ----

	/**
	 * Adaptive payloads for plain enforcement raids only: a heist runs its
	 * scripted ground op (defenses + vault industry, in order), and a vendetta
	 * saturation strike has params.bombardment set (which routes around this
	 * hook entirely).
	 */
	@Override
	public boolean hasCustomRaidAction() {
		return !heist && params.raidParams.bombardment == null;
	}

	@Override
	public void doCustomRaidAction(CampaignFleetAPI fleet, MarketAPI market, float raidStr) {
		// 1: sound military doctrine - if the ground defenses stand and this
		// fleet cannot break them, soften them with shells FIRST (once per
		// contingent). The bombardment disrupts the very defenses repelling
		// the landing parties, opening the ground for the fleets behind.
		float defenderStr = MarketCMD.getDefenderStr(market);
		boolean canBreak = raidStr >= defenderStr * CommWarsConfig.groundBreakMargin();
		if (!canBreak && allowBombard && !didBombard && canAffordBombardment(fleet, market)) {
			didBombard = true;
			new MarketCMD(market.getPrimaryEntity())
					.doBombardment(getFaction(), BombardType.TACTICAL);
			logOperation("Tactical bombardment of " + market.getName()
					+ " - shelled the standing ground defenses open");
			CommWarsConfig.log("  adaptive raid (" + params.factionId + "): defenses too "
					+ "strong to storm (fleet raid str " + (int) raidStr + " vs defender str "
					+ (int) defenderStr + ") - opening TACTICAL BOMBARDMENT of "
					+ market.getName());
			return;
		}

		// 2: the first of our grievance industries still actually running
		if (params.raidParams.disrupt != null) {
			for (String industryId : params.raidParams.disrupt) {
				Industry ind = market.getIndustry(industryId);
				if (ind == null || ind.isDisrupted()) continue;
				float durMult = Global.getSettings()
						.getFloat("punitiveExpeditionDisruptDurationMult");
				boolean disrupted = new MarketCMD(market.getPrimaryEntity())
						.doIndustryRaid(getFaction(), raidStr, ind, durMult);
				logOperation(disrupted
						? "Raid on " + market.getName() + " - disrupted " + ind.getCurrentName()
						: "Landing at " + market.getName() + " aimed at " + ind.getCurrentName()
								+ " - repelled by ground defenses");
				CommWarsConfig.log("  adaptive raid (" + params.factionId + "): "
						+ (disrupted ? "DISRUPTED " : "attempt REPELLED on ")
						+ industryId + " at " + market.getName()
						+ " (fleet raid str " + (int) raidStr + ")");
				return;
			}
		}

		// 3: everything relevant already lies silent - a mop-up bombardment,
		// once per contingent, if the doctrine and the fuel bunkers allow it
		if (allowBombard && !didBombard && canAffordBombardment(fleet, market)) {
			didBombard = true;
			new MarketCMD(market.getPrimaryEntity())
					.doBombardment(getFaction(), BombardType.TACTICAL);
			logOperation("Tactical bombardment of " + market.getName()
					+ " - the targeted industries already lay silent");
			CommWarsConfig.log("  adaptive raid (" + params.factionId + "): target industries "
					+ "already disrupted - TACTICAL BOMBARDMENT of " + market.getName());
			return;
		}

		// 4: nothing left worth aiming at - raid the stockpiles instead
		new MarketCMD(market.getPrimaryEntity()).doGenericRaid(getFaction(), raidStr,
				params.raidParams.maxStabilityLostPerRaid, params.raidParams.raidsPerColony > 1);
		logOperation("Raided " + market.getName() + "'s stockpiles");
		CommWarsConfig.log("  adaptive raid (" + params.factionId + "): generic raid on "
				+ market.getName());
	}

	protected void logOperation(String entry) {
		if (operationsLog == null) operationsLog = new java.util.ArrayList<String>();
		operationsLog.add(entry);
	}

	public java.util.List<String> getOperationsLog() {
		if (operationsLog == null) operationsLog = new java.util.ArrayList<String>();
		return operationsLog;
	}

	/** The same fuel-cost gate vanilla applies to its own fleet bombardments. */
	protected boolean canAffordBombardment(CampaignFleetAPI fleet, MarketAPI market) {
		if (fleet == null) return true; // autoresolve-ish path: no bunker to check
		float cost = MarketCMD.getBombardmentCost(market, fleet);
		return cost <= fleet.getCargo().getMaxFuel() * 0.5f;
	}

	/**
	 * Migration for raids already in flight in an older save: they were
	 * launched with TACTICAL bombardment locked into their params, from before
	 * payloads became adaptive. The raid action reads those params live, so
	 * stripping the locked bombardment and handing the contingent its
	 * industry hit-list converts it mid-flight - new orders by comm relay.
	 * Saturation (vendetta) and heist raids are never converted. Idempotent.
	 */
	public void retrofitAdaptive(java.util.List<String> industries, boolean allowBombard) {
		if (heist) return;
		if (params.raidParams.bombardment != BombardType.TACTICAL) return;
		params.raidParams.bombardment = null;
		if (industries != null && !industries.isEmpty()) {
			params.raidParams.setDisrupt(industries.toArray(new String[0]));
		}
		this.allowBombard = allowBombard;
		CommWarsConfig.log("Retrofitted in-flight raid by " + params.factionId
				+ ": locked TACTICAL bombardment -> adaptive payload"
				+ (industries != null && !industries.isEmpty()
					? " (hit-list " + industries + ")" : ""));
	}

	@Override
	protected void addPostAssessmentSection(TooltipMakerAPI info, float width, float height, float opad) {
		super.addPostAssessmentSection(info, width, height, opad);
		if (params.raidParams.bombardment != null) return; // bombardment, not a ground raid
		EnforcementStrike.appendGroundAssessment(info, getFleets(), getGroundTarget(), heist,
				projectedRaidStr, opad);
	}

	/**
	 * After the vanilla status ("successful and withdrawing"), an operations
	 * report: what this contingent actually did, action by action - which
	 * industries it disrupted, where its landings were repelled, whether the
	 * shells fell. Filled in as the payload decisions are made in real time.
	 */
	@Override
	protected void addStatusSection(TooltipMakerAPI info, float width, float height, float opad) {
		super.addStatusSection(info, width, height, opad);
		if (operationsLog == null || operationsLog.isEmpty()) return;
		info.addSectionHeading("Operations report",
				getFaction().getBaseUIColor(), getFaction().getDarkUIColor(),
				com.fs.starfarer.api.ui.Alignment.MID, opad);
		for (String entry : operationsLog) {
			boolean bad = entry.contains("bombardment") || entry.contains("disrupted")
					|| entry.contains("stockpiles");
			info.addPara(BULLET + entry,
					bad ? com.fs.starfarer.api.util.Misc.getNegativeHighlightColor()
						: com.fs.starfarer.api.util.Misc.getPositiveHighlightColor(),
					3f);
		}
	}
}
