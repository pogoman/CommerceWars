package commwars;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.comm.CommMessageAPI.MessageClickAction;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.EventFactor;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.IntelUIAPI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The Commerce Wars grievance screen: one per aggrieved faction, built on
 * the same event framework as the vanilla colony crisis intel. The bar is
 * the faction's resentment over the player out-competing it; stages are
 * escalation points on the road from diplomatic notes to enforcement fleets.
 *
 * Phase 1: accrual, warning, ultimatum announcement, compliance detection
 * (automatic - the bar drains once the player's share falls), and calm-based
 * expiry. Enforcement fleets, tribute, and the orders dialog are later phases.
 */
public class GrievanceEventIntel extends BaseEventIntel {

	public static enum Stage {
		START,
		WARNING,
		ULTIMATUM,
		ENFORCEMENT,
	}

	public static enum Orders {
		DEFY,
		TRIBUTE,
	}

	public static final int MAX_PROGRESS = 600;
	public static final int WARNING_PROGRESS = 200;
	public static final int ULTIMATUM_PROGRESS = 400;
	public static final int ENFORCEMENT_PROGRESS = 600;

	public static final String BUTTON_ORDERS = "commwars_button_orders";

	protected String factionId;
	protected Map<String, ShareTracker.Cause> causes = new LinkedHashMap<String, ShareTracker.Cause>();
	protected MilitaryScore.MilCause militaryCause = null;
	protected boolean wasCommissioned = false;
	protected Long calmSince = null;

	protected Orders orders = Orders.DEFY;
	protected float tributeMult = 1f;
	protected Long tributeLockoutStart = null;
	protected int escalation = 0;
	protected int totalTributePaid = 0;

	protected GenericRaidFGI strike = null;
	// a coalition strikes as several raids at once, one per member under its own
	// flag; the anchor's is `strike` (tracked for consequences), the partners'
	// contingents are here so the ground assault is judged on the COMBINED force
	protected List<GenericRaidFGI> supportRaids = new ArrayList<GenericRaidFGI>();
	protected MarketAPI strikeTarget = null;
	protected MarketAPI strikeSource = null;
	protected List<String> strikeCommodities = null;
	protected boolean strikeWasTacBomb = false;
	protected String strikeHeistIndustryId = null;
	protected boolean strikeMilitaryMode = false;
	protected Float cachedRaidStr = null;
	protected Float cachedDefenderStr = null;
	protected int cachedFleetCount = 0;

	protected Long truceStart = null;
	protected float truceDays = 0f;
	protected Long lastStrikeEnd = null;

	protected boolean emboldened = true;
	protected List<String> coalitionPartners = new ArrayList<String>();
	protected List<String> strikePartners = null;
	protected String supportingStrikeFor = null;
	protected boolean vendetta = false;

	/** The active grievance intel for a faction, if any. */
	public static GrievanceEventIntel get(String factionId) {
		for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin p
				: Global.getSector().getIntelManager().getIntel(GrievanceEventIntel.class)) {
			GrievanceEventIntel intel = (GrievanceEventIntel) p;
			if (intel.isEnding() || intel.isEnded()) continue;
			if (intel.getFactionId().equals(factionId)) return intel;
		}
		return null;
	}

	protected String lastStrikeMath = null;

	public GrievanceEventIntel(String factionId, List<ShareTracker.Cause> initialCauses) {
		super();
		this.factionId = factionId;

		setMaxProgress(MAX_PROGRESS);
		addStage(Stage.START, 0);
		addStage(Stage.WARNING, WARNING_PROGRESS, StageIconSize.MEDIUM);
		addStage(Stage.ULTIMATUM, ULTIMATUM_PROGRESS, StageIconSize.LARGE);
		addStage(Stage.ENFORCEMENT, ENFORCEMENT_PROGRESS, true, StageIconSize.LARGE);

		// stage announcements are handled by explicit messages in
		// notifyStageReached - the built-in update path silently drops
		// updates if the intel was never player-visible
		for (EventStageData esd : getStages()) {
			esd.sendIntelUpdateOnReaching = false;
		}

		// full factor roster (decay, suppression, military, gate, vendetta);
		// commodity factors are synced per-cause by updateCauses
		ensureFactors();
		updateCauses(initialCauses);

		// now that the event is fully constructed, add it and send notification
		Global.getSector().getIntelManager().addIntel(this);
	}

	public String getFactionId() {
		return factionId;
	}

	public FactionAPI getFaction() {
		return Global.getSector().getFaction(factionId);
	}

	// ---- cause snapshot, maintained by GrievanceManager ----

	public ShareTracker.Cause getCause(String commodityId) {
		return causes.get(commodityId);
	}

	public boolean hasCauses() {
		return !causes.isEmpty() || militaryCause != null;
	}

	public float totalCauseWeight() {
		float total = 0f;
		for (ShareTracker.Cause c : causes.values()) total += c.weight;
		if (militaryCause != null) total += militaryCause.weight;
		return total;
	}

	public MilitaryScore.MilCause getMilitaryCause() {
		return militaryCause;
	}

	public void setMilitaryCause(MilitaryScore.MilCause militaryCause) {
		this.militaryCause = militaryCause;
	}

	/** The military concern outweighs the trade one (or is the only one). */
	public boolean isMilitaryDominant() {
		if (militaryCause == null) return false;
		float econ = 0f;
		for (ShareTracker.Cause c : causes.values()) econ += c.weight;
		return causes.isEmpty() || militaryCause.weight >= econ;
	}

	/**
	 * Commission tracking: serving under this faction's flag suppresses the
	 * military cause (handled in MilitaryScore.computeCause); *resigning* that
	 * commission is an insult that comes with a resentment head start.
	 */
	public void updateCommissionState() {
		boolean now = factionId.equals(Misc.getCommissionFactionId());
		if (wasCommissioned && !now) {
			addFactor(new RetaliationFactor(CommWarsConfig.commissionLapseSpike(),
					"Resigned their commission"));
			CommWarsConfig.log("Commission with " + factionId + " ended: +"
					+ CommWarsConfig.commissionLapseSpike() + " resentment");
			announce("Word spreads that you no longer serve under "
					+ getFaction().getDisplayNameWithArticle() + "'s flag. Your arsenal is "
					+ "your own again - and once more a subject of their close attention.",
					Misc.getNegativeHighlightColor());
		}
		wasCommissioned = now;
	}

	public List<String> getCauseCommodityIds() {
		return new ArrayList<String>(causes.keySet());
	}

	/**
	 * Total monthly resentment from commodity causes, mirroring the per-cause
	 * rounding of {@link CommodityGrievanceFactor} so tribute at the expected
	 * level nets to exactly zero.
	 */
	public int getMonthlyCauseAccrual() {
		int total = 0;
		for (ShareTracker.Cause c : causes.values()) {
			total += Math.round(c.weight * CommWarsConfig.clockMult());
		}
		if (militaryCause != null) {
			total += Math.round(militaryCause.weight * CommWarsConfig.clockMult());
		}
		return total;
	}

	/** Retrofit/migrate state from older-save grievances. */
	public void ensureFactors() {
		if (getFactorOfClass(GrievanceDecayFactor.class) == null) {
			addFactor(new GrievanceDecayFactor());
		}
		if (getFactorOfClass(SuppressionFactor.class) == null) {
			addFactor(new SuppressionFactor());
		}
		if (getFactorOfClass(MilitaryGrievanceFactor.class) == null) {
			addFactor(new MilitaryGrievanceFactor());
		}
		if (getFactorOfClass(GateFactor.class) == null) {
			addFactor(new GateFactor());
		}
		if (getFactorOfClass(VendettaFactor.class) == null) {
			addFactor(new VendettaFactor());
		}
		// recurring tribute was replaced by one-off settlements: drop stale
		// factor instances and pledge state from older saves
		EventFactor stale = getFactorOfClass(TributeFactor.class);
		if (stale != null) {
			removeFactor(stale);
		}
		if (orders == Orders.TRIBUTE) {
			orders = Orders.DEFY;
		}
	}

	// ---- orders, truce, strike state ----

	public Orders getOrders() {
		return orders;
	}

	public void setOrders(Orders orders) {
		this.orders = orders;
	}

	/** One-off settlement: buy down resentment for credits (already deducted). */
	public void paySettlement(int points, int cost) {
		setProgress(Math.max(0, getProgress() - points));
		totalTributePaid += cost;
	}

	// ---- strength gate & coalition state, maintained by GrievanceManager ----

	/** Bar cap while the faction lacks the strength to press demands. */
	public static final int GATE_CAP = ULTIMATUM_PROGRESS - 10;

	public boolean isEmboldened() {
		return emboldened;
	}

	public List<String> getCoalitionPartners() {
		return coalitionPartners;
	}

	public boolean isCoalitionBacked() {
		return emboldened && !coalitionPartners.isEmpty();
	}

	/** Other active grievances that count this faction among their coalition partners. */
	public List<String> getBackedFactions() {
		List<String> result = new ArrayList<String>();
		for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin p
				: Global.getSector().getIntelManager().getIntel(GrievanceEventIntel.class)) {
			GrievanceEventIntel other = (GrievanceEventIntel) p;
			if (other == this || other.isEnding() || other.isEnded()) continue;
			if (other.isCoalitionBacked() && other.getCoalitionPartners().contains(factionId)) {
				result.add(other.getFactionId());
			}
		}
		return result;
	}

	public String getPartnerNames(List<String> ids) {
		List<String> names = new ArrayList<String>();
		for (String id : ids) {
			FactionAPI faction = Global.getSector().getFaction(id);
			names.add(faction != null ? faction.getDisplayName() : id);
		}
		return Misc.getAndJoined(names.toArray(new String[0]));
	}

	/**
	 * A labelled bullet listing faction names, each highlighted in its own
	 * faction colour (e.g. "In coalition with: Tri-Tachyon, the independents,
	 * and the Hegemony" with each name in its faction's UI colour).
	 */
	protected void addFactionListPara(TooltipMakerAPI info, String label, List<String> ids,
									  float pad) {
		if (ids == null || ids.isEmpty()) return;
		List<String> names = new ArrayList<String>();
		List<Color> colors = new ArrayList<Color>();
		for (String id : ids) {
			FactionAPI f = Global.getSector().getFaction(id);
			names.add(f != null ? f.getDisplayName() : id);
			colors.add(f != null ? f.getBaseUIColor() : Misc.getHighlightColor());
		}
		info.addPara(label + ": " + Misc.getAndJoined(names.toArray(new String[0])), pad,
				colors.toArray(new Color[0]), names.toArray(new String[0]));
	}

	/**
	 * Add a prose paragraph, colouring this faction's name and any coalition,
	 * strike, or backed-partner name that appears in it - each in its own
	 * faction colour, the same way the announcement messages are coloured.
	 */
	protected void addColoredFactionPara(TooltipMakerAPI info, String text, float pad) {
		List<String> names = new ArrayList<String>();
		List<Color> colors = new ArrayList<Color>();
		names.add(getFaction().getDisplayName());
		colors.add(getFaction().getBaseUIColor());
		List<String> others = new ArrayList<String>(coalitionPartners);
		if (strikePartners != null) {
			for (String id : strikePartners) if (!others.contains(id)) others.add(id);
		}
		for (String id : getBackedFactions()) if (!others.contains(id)) others.add(id);
		for (String id : others) {
			FactionAPI f = Global.getSector().getFaction(id);
			if (f == null || !text.contains(f.getDisplayName())) continue;
			names.add(f.getDisplayName());
			colors.add(f.getBaseUIColor());
		}
		info.addPara(text, pad, colors.toArray(new Color[0]), names.toArray(new String[0]));
	}

	public void setGateState(boolean nowEmboldened, List<String> partners) {
		boolean wasEmboldened = this.emboldened;
		boolean wasCoalition = isCoalitionBacked();

		this.coalitionPartners = partners == null ? new ArrayList<String>() : partners;
		this.emboldened = nowEmboldened;

		if (wasEmboldened && !nowEmboldened) {
			CommWarsConfig.log("Grievance with " + factionId + " GATED (too weak to press)");
			announce(Misc.ucFirst(getFaction().getDisplayNameWithArticle())
					+ " seethes over its grievances against you - but lacks the strength to "
					+ "press them, even counting every ally it could rally. For now, it can "
					+ "only watch.", Misc.getPositiveHighlightColor());
		} else if (!wasCoalition && isCoalitionBacked()) {
			CommWarsConfig.log("Grievance with " + factionId + " now COALITION-backed: "
					+ coalitionPartners);
			announce("Unable to face your power alone, "
					+ getFaction().getDisplayNameWithArticle()
					+ " has assembled a coalition to press its grievance - joined by "
					+ getPartnerNames(coalitionPartners) + ".",
					Misc.getNegativeHighlightColor());
		}
	}

	/** Hold the bar below the ultimatum line while gated. */
	@Override
	public void reportEconomyTick(int iterIndex) {
		super.reportEconomyTick(iterIndex);
		if (!emboldened && getProgress() > GATE_CAP) {
			setProgress(GATE_CAP);
		}
	}

	public int getEscalation() {
		return escalation;
	}

	public int getTotalTributePaid() {
		return totalTributePaid;
	}

	public void addTributePaid(int amount) {
		totalTributePaid += amount;
	}

	public boolean isUltimatumReached() {
		EventStageData d = getDataFor(Stage.ULTIMATUM);
		return d != null && d.wasEverReached;
	}

	public boolean isInTruce() {
		if (truceStart == null) return false;
		float days = Global.getSector().getClock().getElapsedDaysSince(truceStart);
		if (days >= truceDays) {
			truceStart = null;
			truceDays = 0f;
			return false;
		}
		return true;
	}

	public float getTruceDaysLeft() {
		if (truceStart == null) return 0f;
		float days = Global.getSector().getClock().getElapsedDaysSince(truceStart);
		return Math.max(0f, truceDays - days);
	}

	/**
	 * Resentment accrual is frozen while a truce holds, an enforcement strike
	 * is underway, or this faction's contingents are committed to a coalition
	 * partner's strike - the joint action speaks for every member's grievance.
	 */
	public boolean isAccrualSuppressed() {
		return isInTruce() || isStrikeActive() || supportingStrikeFor != null
				|| isDeferredToVanillaCrisis() || isCommissionSuppressed();
	}

	/**
	 * You serve under this faction's flag: a patron does not hand its own
	 * commissioned client trade ultimatums. The whole grievance freezes
	 * while the commission holds (resigning resumes it, plus the military
	 * spike from updateCommissionState).
	 */
	public boolean isCommissionSuppressed() {
		if (vendetta) return false; // a blood feud honors no commission
		return factionId.equals(Misc.getCommissionFactionId());
	}

	/** The vanilla colony-crisis system already speaks for this faction. */
	public boolean isDeferredToVanillaCrisis() {
		if (vendetta) return false; // the blood feud defers to nothing
		return VanillaCrisis.isActiveOrPending(factionId);
	}

	/** Gated and pinned at the cap: accrual has nowhere left to go. */
	public boolean isGateCapped() {
		return !emboldened && getProgress() >= GATE_CAP;
	}

	// ---- vendetta: the blood feud after a saturation bombardment ----

	public boolean isVendetta() {
		return vendetta;
	}

	/**
	 * The player saturation-bombed one of this faction's worlds: the
	 * grievance converts permanently into a blood feud. No settlements, no
	 * tribute, no truces, no expiry - their strikes become saturation
	 * bombardments, bounded only by what their military-industrial base can
	 * still field. Ends when one side has no worlds left.
	 */
	public void declareVendetta(MarketAPI bombedMarket) {
		ensureFactors();
		String bombedName = bombedMarket != null ? bombedMarket.getName() : "their world";
		if (!vendetta) {
			vendetta = true;
			orders = Orders.DEFY;
			truceStart = null;
			truceDays = 0f;
			setProgress(Math.max(getProgress(), CommWarsConfig.vendettaStartProgress()));
			CommWarsConfig.log("VENDETTA declared by " + factionId
					+ " (sat bomb of " + bombedName + ")");
			announce(Misc.ucFirst(getFaction().getDisplayNameWithArticle())
					+ " will never forgive the burning of " + bombedName + ". There will be "
					+ "no notes, no ultimatums, no settlements - only retribution, whenever "
					+ "and however they are able.", Misc.getNegativeHighlightColor());
		} else {
			// another atrocity feeds the same feud
			escalation++;
			setProgress(Math.max(getProgress(), CommWarsConfig.vendettaStartProgress()));
			CommWarsConfig.log("Vendetta with " + factionId + " deepened by another atrocity ("
					+ bombedName + "), escalation now " + escalation);
		}
	}

	/** The feud ends only when there is nothing left to avenge it with. */
	public void endVendetta() {
		CommWarsConfig.log("Vendetta with " + factionId + " ends: no worlds remain to pursue it");
		endAfterDelay();
	}

	/**
	 * A permanent pather agreement now stands (the vanilla "holy peace until the
	 * End of Days", however earned): end this grievance for good. Peace overrides
	 * everything - even a blood feud - because the agreement is itself the Path
	 * standing down permanently. Idempotent; the manager will not reopen a Path
	 * grievance while the agreement holds.
	 */
	public void endForPatherPeace() {
		if (isEnding() || isEnded()) return;
		// call off any strike already in flight so it does not resolve after peace
		if (isStrikeActive()) {
			clearStrike();
		}
		CommWarsConfig.log("Grievance with " + factionId
				+ " ends: permanent pather agreement (holy peace) in effect");
		announce("An understanding stands between your polity and "
				+ getFaction().getDisplayNameWithArticle() + ": holy peace, until the End of "
				+ "Days. Their cells stand down and their grievance is laid to rest - "
				+ "permanently.", Misc.getPositiveHighlightColor());
		endAfterDelay();
	}

	public String getSupportingStrikeFor() {
		return supportingStrikeFor;
	}

	/**
	 * This faction joins a coalition partner's enforcement strike: its own
	 * ledger vents into the joint action - reset and held for the duration.
	 */
	public void joinCoalitionStrike(String anchorFactionId) {
		supportingStrikeFor = anchorFactionId;
		setProgress(Math.min(getProgress(), CommWarsConfig.strikeResetProgress()));
		CommWarsConfig.log("Grievance with " + factionId + " committed to coalition strike led by "
				+ anchorFactionId + " (bar reset + held)");
	}

	/**
	 * Release coalition partners when the joint strike resolves. Success
	 * shares the truce with the whole bloc; either way everyone shares the
	 * post-strike cooldown. Idempotent.
	 */
	protected void releasePartners(boolean sharedTruce) {
		if (strikePartners == null) return;
		for (String partnerId : strikePartners) {
			GrievanceEventIntel partner = get(partnerId);
			if (partner == null) continue;
			if (!factionId.equals(partner.supportingStrikeFor)) continue;
			partner.supportingStrikeFor = null;
			partner.lastStrikeEnd = Global.getSector().getClock().getTimestamp();
			if (sharedTruce) {
				partner.truceStart = Global.getSector().getClock().getTimestamp();
				partner.truceDays = CommWarsConfig.truceDaysAfterStrike();
			}
			CommWarsConfig.log("Released " + partnerId + " from coalition strike"
					+ (sharedTruce ? " (shared truce)" : ""));
		}
	}

	/** Safety net: clear a dangling support state if the anchor's strike is gone. */
	public void validateSupportState() {
		if (supportingStrikeFor == null) return;
		GrievanceEventIntel anchor = get(supportingStrikeFor);
		if (anchor == null || !anchor.isStrikeActive()) {
			supportingStrikeFor = null;
		}
	}

	/** Responding (settlements) is only possible before fleets fly - and never in a blood feud. */
	public boolean canRespond() {
		return !vendetta && isUltimatumReached() && !isStrikeActive() && !isEnding() && !isEnded();
	}

	public GenericRaidFGI getStrike() {
		return strike;
	}

	public String getLastStrikeMath() {
		return lastStrikeMath;
	}

	public void setLastStrikeMath(String lastStrikeMath) {
		this.lastStrikeMath = lastStrikeMath;
	}

	/**
	 * The joint operation is active while ANY contingent - the anchor's raid
	 * or a coalition partner's - is still in play. The anchor's fleets being
	 * wiped does not end a coalition strike whose partners are still inbound.
	 */
	public boolean isStrikeActive() {
		if (strike != null && !strike.isEnded() && !strike.isEnding()) return true;
		if (supportRaids != null) {
			for (GenericRaidFGI raid : supportRaids) {
				if (raid != null && !raid.isEnded() && !raid.isEnding()) return true;
			}
		}
		return false;
	}

	/**
	 * Convert any of this strike's raids still carrying launch-time TACTICAL
	 * bombardment orders (from a save made before payloads became adaptive)
	 * to the adaptive payload, each contingent handed the hit-list for its
	 * own grievance. Called by the manager's strike poll; idempotent.
	 */
	public void retrofitAdaptiveRaids() {
		if (vendetta) return;
		if (strikeTarget == null) return;
		// old saves recorded the launch-time bombardment intent on the
		// grievance too; only a vendetta is a committed bombardment now
		strikeWasTacBomb = false;
		List<GenericRaidFGI> all = new ArrayList<GenericRaidFGI>();
		if (strike != null) all.add(strike);
		if (supportRaids != null) all.addAll(supportRaids);
		for (GenericRaidFGI raid : all) {
			if (!(raid instanceof EnforcementRaidFGI)) continue;
			if (raid.isEnded() || raid.isEnding()) continue;
			String raidFactionId = raid.getParams() != null
					? raid.getParams().factionId : factionId;
			GrievanceEventIntel memberIntel = factionId.equals(raidFactionId)
					? this : get(raidFactionId);
			List<String> industries = EnforcementStrike.memberDisruptIndustries(
					memberIntel, strikeTarget,
					strikeCommodities != null ? strikeCommodities : getCauseCommodityIds(),
					strikeMilitaryMode);
			((EnforcementRaidFGI) raid).retrofitAdaptive(industries,
					escalation >= CommWarsConfig.tacBombEscalation());
		}
	}

	/**
	 * Every contingent's fleet-by-fleet operations, faction-prefixed when more
	 * than one faction flew - the ground truth the after-action summary must
	 * agree with.
	 */
	protected List<String> collectOperations() {
		List<String> result = new ArrayList<String>();
		List<GenericRaidFGI> all = new ArrayList<GenericRaidFGI>();
		if (strike != null) all.add(strike);
		if (supportRaids != null) all.addAll(supportRaids);
		boolean multi = all.size() > 1;
		for (GenericRaidFGI raid : all) {
			if (!(raid instanceof EnforcementRaidFGI)) continue;
			String prefix = "";
			if (multi && raid.getParams() != null && raid.getParams().factionId != null) {
				FactionAPI f = Global.getSector().getFaction(raid.getParams().factionId);
				if (f != null) prefix = f.getDisplayName() + ": ";
			}
			for (String entry : ((EnforcementRaidFGI) raid).getOperationsLog()) {
				result.add(prefix + entry);
			}
		}
		return result;
	}

	/** This contingent's fate is settled - succeeded, beaten, or otherwise over. */
	protected boolean raidResolved(GenericRaidFGI raid) {
		if (raid == null) return true;
		return raid.isSucceeded() || raid.isFailed() || raid.isAborted()
				|| raid.isEnding() || raid.isEnded();
	}

	/**
	 * Every contingent of the joint operation has run its course - only then
	 * is the strike as a whole judged. An individual contingent being wiped
	 * while others still sail is not an outcome, just a casualty report.
	 */
	public boolean allStrikeRaidsResolved() {
		if (!raidResolved(strike)) return false;
		if (supportRaids != null) {
			for (GenericRaidFGI raid : supportRaids) {
				if (!raidResolved(raid)) return false;
			}
		}
		return true;
	}

	/**
	 * Did ANY contingent complete its raid? A coalition strike succeeds as a
	 * whole if any member's forces delivered their payload - and is only
	 * DEFEATED when every last contingent was beaten or driven off.
	 */
	public boolean anyStrikeRaidSucceeded() {
		if (strike != null && strike.isSucceeded()) return true;
		if (supportRaids != null) {
			for (GenericRaidFGI raid : supportRaids) {
				if (raid != null && raid.isSucceeded()) return true;
			}
		}
		return false;
	}

	public void setStrike(GenericRaidFGI strike, MarketAPI target, MarketAPI source,
						  boolean tacBomb, boolean militaryMode, String heistIndustryId) {
		this.strike = strike;
		this.strikeTarget = target;
		this.strikeSource = source;
		this.strikeWasTacBomb = tacBomb;
		// military strikes break things rather than looting trade goods
		this.strikeCommodities = militaryMode
				? new ArrayList<String>() : getCauseCommodityIds();
		this.strikeHeistIndustryId = heistIndustryId;
		this.strikeMilitaryMode = militaryMode;
		this.strikePartners = new ArrayList<String>(coalitionPartners);
		if (supportRaids == null) supportRaids = new ArrayList<GenericRaidFGI>();
		supportRaids.clear();
	}

	/** Register a coalition partner's contingent flying alongside the anchor's raid. */
	public void addSupportRaid(GenericRaidFGI raid) {
		if (supportRaids == null) supportRaids = new ArrayList<GenericRaidFGI>();
		if (raid != null) supportRaids.add(raid);
	}

	/**
	 * Every fleet attacking the ANCHOR'S target colony - the anchor's raid
	 * plus any coalition contingent striking the same colony - so its ground
	 * assault is judged on the force actually landing there. Contingents
	 * hitting OTHER colonies (each chases its own grievance's producer) fight
	 * their own ground battles via their adaptive payloads and are excluded.
	 */
	public List<com.fs.starfarer.api.campaign.CampaignFleetAPI> allStrikeFleets() {
		List<com.fs.starfarer.api.campaign.CampaignFleetAPI> result =
				new ArrayList<com.fs.starfarer.api.campaign.CampaignFleetAPI>();
		if (strike != null) result.addAll(strike.getFleets());
		if (supportRaids != null) {
			for (GenericRaidFGI raid : supportRaids) {
				if (raid == null || raid.isEnded() || raid.isEnding()) continue;
				if (raid instanceof EnforcementRaidFGI && strikeTarget != null
						&& ((EnforcementRaidFGI) raid).getGroundTarget() != strikeTarget) {
					continue; // fighting on another front
				}
				result.addAll(raid.getFleets());
			}
		}
		return result;
	}

	public void clearStrike() {
		releasePartners(false); // no-op for partners already released with a truce
		strike = null;
		if (supportRaids != null) supportRaids.clear();
		strikeTarget = null;
		strikeSource = null;
		strikeCommodities = null;
		strikeWasTacBomb = false;
		strikeHeistIndustryId = null;
		strikeMilitaryMode = false;
		cachedRaidStr = null;
		cachedDefenderStr = null;
		strikePartners = null;
		lastStrikeEnd = Global.getSector().getClock().getTimestamp();
	}

	public float daysSinceLastStrikeEnd() {
		if (lastStrikeEnd == null) return Float.MAX_VALUE;
		return Global.getSector().getClock().getElapsedDaysSince(lastStrikeEnd);
	}

	/**
	 * Did the raiders actually overcome the colony's CURRENT ground defenses?
	 * The same check the game applies to the player's own raids: total raid
	 * strength across the strike's surviving fleets versus the target's
	 * defender strength (which now includes any Planetary Shield, marines in
	 * cargo, etc.). If they cannot break the ground, they can neither steal
	 * nor disrupt - exactly as it works when the player raids. Cached once the
	 * raid connects (fleets still present), since defenses may have grown
	 * since the fleet was committed.
	 */
	/** Measure the raiders' ground strength vs the target's current defenses (cached). */
	protected void measureGroundAssault() {
		if (cachedRaidStr != null) return;
		if (strike == null || strikeTarget == null) return;
		float raidStr = 0f;
		int fleets = 0;
		// count every coalition contingent's fleets, not just the anchor's, so
		// the ground assault is judged on the combined force that actually lands
		for (com.fs.starfarer.api.campaign.CampaignFleetAPI fleet : allStrikeFleets()) {
			raidStr += com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD
					.getRaidStr(fleet);
			fleets++;
		}
		if (raidStr <= 0f) return; // fleets not present yet / all gone - retry later
		cachedRaidStr = raidStr;
		cachedFleetCount = fleets;
		cachedDefenderStr = com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD
				.getDefenderStr(strikeTarget);
		CommWarsConfig.log("Ground assault on " + strikeTarget.getName() + ": " + fleets
				+ " fleets, raid str " + (int) cachedRaidStr.floatValue()
				+ " vs defender str " + (int) cachedDefenderStr.floatValue());
	}

	/**
	 * Did the raiders overcome the target's CURRENT ground defenses at the
	 * given advantage margin? The same measure the game uses for the player's
	 * own raids (fleet raid strength vs defender strength, defender strength
	 * including any Planetary Shield and cargo marines). margin 1.0 = enough
	 * to raid/disrupt; a much higher margin = the overwhelming advantage
	 * needed to lift a strategic asset.
	 */
	public boolean brokeGround(float margin) {
		measureGroundAssault();
		if (cachedRaidStr == null) return false;
		return cachedRaidStr >= cachedDefenderStr * margin;
	}

	/** Broke the ground enough to raid/disrupt at all. */
	public boolean brokeGroundDefenses() {
		return brokeGround(CommWarsConfig.groundBreakMargin());
	}

	/**
	 * A live projection of the inbound strike's ground assault against the
	 * target's CURRENT defenses - shown on the grievance screen so the player
	 * can judge whether their defenses will hold before the fleets arrive,
	 * regardless of what the vanilla (space-only) FGI assessment says.
	 */
	public void addGroundAssessment(TooltipMakerAPI info) {
		if (strike == null || strikeTarget == null) return;
		if (strikeWasTacBomb) {
			info.addPara("Their intent is bombardment - a planetary shield blunts it, but "
					+ "ground defenses will not stop the shells.", 5f);
			return;
		}
		// project the combined force converging on THIS colony (anchor plus any
		// contingent striking the same target), matching how the ground assault
		// is actually judged when it connects - other-front contingents excluded
		float projected = 0f;
		if (strike instanceof EnforcementRaidFGI) {
			projected += ((EnforcementRaidFGI) strike).getProjectedRaidStr();
		}
		if (supportRaids != null) {
			for (GenericRaidFGI raid : supportRaids) {
				if (raid instanceof EnforcementRaidFGI && !raid.isEnded() && !raid.isEnding()
						&& ((EnforcementRaidFGI) raid).getGroundTarget() == strikeTarget) {
					projected += ((EnforcementRaidFGI) raid).getProjectedRaidStr();
				}
			}
		}
		EnforcementStrike.appendGroundAssessment(info, allStrikeFleets(), strikeTarget,
				strikeHeistIndustryId != null, projected, 5f);
	}

	/**
	 * When the raid connects, attempt the item heist - but ONLY if the
	 * raiders have the overwhelming ground advantage required to lift a
	 * strategic asset. Called every manager tick while a strike is active.
	 */
	public void checkStrikeRaidHit() {
		if (strike == null) return;
		if (strike.isAborted() || strike.isFailed()) return;
		if (strike.getRaidAction() == null) return;
		if (strike.getRaidAction().getSuccessFraction() <= 0f) return;

		// measure the ground assault now, while the fleets are still present -
		// the outcome must not depend on fleets having despawned by resolution
		measureGroundAssault();

		// item heist only on an overwhelming advantage
		if (strikeHeistIndustryId != null && brokeGround(CommWarsConfig.heistBreakMargin())) {
			performItemTheft();
		}
	}

	/** The heist payoff: lift the installed item and take it somewhere real. */
	protected void performItemTheft() {
		if (strikeTarget == null || strikeHeistIndustryId == null) return;
		String heistIndustryId = strikeHeistIndustryId;
		strikeHeistIndustryId = null;

		Industry ind = strikeTarget.getIndustry(heistIndustryId);
		if (ind == null) return;
		SpecialItemData item = ind.getSpecialItem();
		if (item == null) {
			// the player pulled it out in time - the vault was empty
			CommWarsConfig.log("Heist by " + factionId + " found no item in "
					+ heistIndustryId + " at " + strikeTarget.getName());
			return;
		}
		ind.setSpecialItem(null);

		String itemName = item.getId();
		try {
			itemName = Global.getSettings().getSpecialItemSpec(item.getId()).getName();
		} catch (Throwable t) {
		}

		StolenItems.Record record = new StolenItems.Record();
		record.itemId = item.getId();
		record.itemData = item.getData();
		record.origMarketName = strikeTarget.getName();
		record.factionId = factionId;

		MarketAPI holder = deliverItem(heistIndustryId, item, record);

		StolenItems.add(record);
		CommWarsConfig.log("HEIST: " + factionId + " stole " + item.getId() + " from "
				+ strikeTarget.getName() + " -> "
				+ (holder != null ? holder.getName() : "?")
				+ (record.industryId != null ? " (installed in " + record.industryId + ")"
						: " (held in storage)"));

		announce(Misc.ucFirst(getFaction().getDisplayNameWithArticle()) + " marines have stormed "
				+ strikeTarget.getName() + " and carried off the " + itemName + "! "
				+ (holder != null
					? "Intelligence places it at " + holder.getName()
							+ " - where a raid might yet recover it."
					: "Its whereabouts are unknown."),
				Misc.getNegativeHighlightColor());
	}

	/**
	 * Install the stolen item on one of the faction's own markets (same
	 * industry family, empty slot), falling back to their largest market's
	 * vaults. Fills in the record's holder fields either way.
	 */
	protected MarketAPI deliverItem(String fromIndustryId, SpecialItemData item,
									StolenItems.Record record) {
		List<String> family = new ArrayList<String>();
		family.add(fromIndustryId);
		if (Industries.HEAVYINDUSTRY.equals(fromIndustryId)) {
			family.add(Industries.ORBITALWORKS);
		} else if (Industries.ORBITALWORKS.equals(fromIndustryId)) {
			family.add(Industries.HEAVYINDUSTRY);
		}

		List<MarketAPI> markets = new ArrayList<MarketAPI>(
				Misc.getFactionMarkets(getFaction(), null));
		java.util.Collections.sort(markets, new java.util.Comparator<MarketAPI>() {
			@Override
			public int compare(MarketAPI a, MarketAPI b) {
				return b.getSize() - a.getSize();
			}
		});

		for (MarketAPI market : markets) {
			for (String industryId : family) {
				Industry ind = market.getIndustry(industryId);
				if (ind != null && ind.getSpecialItem() == null) {
					ind.setSpecialItem(item);
					record.holderMarketId = market.getId();
					record.industryId = industryId;
					return market;
				}
			}
		}

		// no usable slot: held at their largest market, recoverable by raid
		if (!markets.isEmpty()) {
			record.holderMarketId = markets.get(0).getId();
			record.industryId = null;
			return markets.get(0);
		}
		return null;
	}


	/**
	 * Disrupt the offending (or military-track) industries for a guaranteed
	 * duration - ONLY called once the raiders have broken the ground defenses,
	 * so it is exactly as legitimate as the theft. Production stops, market
	 * share falls, and the dispute finally has a mechanism to cool. Never used
	 * when defenses held. Returns a summary of what was disrupted.
	 */
	protected String disruptIndustries() {
		if (strikeTarget == null) return null;
		if (strikeWasTacBomb || strikeHeistIndustryId != null) return null; // own effects

		List<String> industries = strikeMilitaryMode
				? EnforcementStrike.findMilitaryIndustries(strikeTarget)
				: EnforcementStrike.findOffendingIndustries(strikeTarget,
						strikeCommodities != null ? strikeCommodities : getCauseCommodityIds());

		float days = CommWarsConfig.strikeDisruptDays();
		StringBuilder sb = new StringBuilder();
		for (String industryId : industries) {
			com.fs.starfarer.api.campaign.econ.Industry ind = strikeTarget.getIndustry(industryId);
			if (ind == null) continue;
			ind.setDisrupted(days, true);
			if (sb.length() > 0) sb.append(", ");
			sb.append(ind.getCurrentName());
			CommWarsConfig.log("  disrupted " + industryId + " on "
					+ strikeTarget.getName() + " for " + (int) days + " days");
		}
		return sb.length() > 0 ? sb.toString() : null;
	}

	/**
	 * Did any contingent of this strike actually fire a bombardment? Raids
	 * decide their payload in real time (see EnforcementRaidFGI), so whether
	 * shells flew is a fact of the raid, not a launch-time intent.
	 */
	public boolean anyBombardmentDone() {
		if (strike instanceof EnforcementRaidFGI
				&& ((EnforcementRaidFGI) strike).isDidBombard()) {
			return true;
		}
		if (supportRaids != null) {
			for (GenericRaidFGI raid : supportRaids) {
				if (raid instanceof EnforcementRaidFGI
						&& ((EnforcementRaidFGI) raid).isDidBombard()) {
					return true;
				}
			}
		}
		return false;
	}

	/** Called by the manager when the strike's fleets complete their operation. */
	public void onStrikeSucceeded() {
		if (strike == null) return;
		boolean broke = brokeGroundDefenses();
		CommWarsConfig.log("Enforcement strike by " + factionId + " on "
				+ (strikeTarget != null ? strikeTarget.getName() : "?")
				+ (broke ? " BROKE THROUGH" : " was REPELLED at the ground"));

		String targetName = strikeTarget != null ? strikeTarget.getName() : "your colony";
		String disruptSummary = null;
		// vendetta = committed saturation; otherwise report what actually happened
		boolean tacBomb = strikeWasTacBomb || anyBombardmentDone();

		if (broke) {
			// they cracked the ground: disruption lands (a heisted item was
			// already lifted at connect-time, gated on a far higher advantage),
			// and they get their truce - the same ground check gates all of it,
			// exactly as it does for the player's own raids
			disruptSummary = disruptIndustries();
			if (!vendetta) {
				truceStart = Global.getSector().getClock().getTimestamp();
				truceDays = CommWarsConfig.truceDaysAfterStrike();
			}
			releasePartners(!vendetta);
		} else {
			// ground defenses held: nothing disrupted, no truce. They escalate,
			// to come harder next time (eventually to bombardment, which a raid
			// cannot substitute for). The bar stays where the launch left it.
			escalation++;
			releasePartners(false);
		}

		// a persistent after-action report
		StrikeSummaryIntel summary = new StrikeSummaryIntel(factionId, targetName);
		summary.setOutcome(broke, cachedFleetCount,
				cachedRaidStr != null ? (int) cachedRaidStr.floatValue() : 0,
				cachedDefenderStr != null ? (int) cachedDefenderStr.floatValue() : 0);
		if (broke && disruptSummary != null) {
			summary.setDisrupt(disruptSummary, (int) CommWarsConfig.strikeDisruptDays());
		}
		// a bombardment is not gated on the ground break - it lands (and does its
		// disruption/stability damage) even when the landing force is repelled
		summary.setBombarded(tacBomb, vendetta);
		// the fleet-by-fleet operations, so the summary's narrative and the
		// blow-by-blow reality can't contradict each other
		summary.setOperations(collectOperations());
		summary.finish();

		clearStrike();

		String text;
		if (vendetta) {
			// a saturation bombardment is not stopped by ground defenses -
			// whatever the ground math said, the shells landed
			text = Misc.ucFirst(getFaction().getDisplayNameWithArticle())
					+ "'s retribution fleets have done their work at " + targetName + ". "
					+ "The blood debt is not settled - it is merely fed.";
		} else if (!broke && tacBomb) {
			text = "Your ground defenses repelled " + Misc.ucFirst(getFaction()
					.getDisplayNameWithArticle()) + "'s landing force at " + targetName
					+ " - nothing was taken on the ground. But their tactical bombardment "
					+ "struck home, disrupting military infrastructure and costing stability. "
					+ "They withdraw, and will return in greater force. See the enforcement "
					+ "damage report in your intel.";
		} else if (!broke) {
			text = "Your ground defenses repelled " + Misc.ucFirst(getFaction()
					.getDisplayNameWithArticle()) + "'s enforcement action against " + targetName
					+ " - nothing taken, nothing disrupted. They withdraw, and will return in "
					+ "greater force. See the enforcement damage report in your intel.";
		} else {
			text = Misc.ucFirst(getFaction().getDisplayNameWithArticle())
					+ " enforcement action against " + targetName + " has run its course.";
			if (disruptSummary != null) text += " Industries disrupted: " + disruptSummary + ".";
			text += " See the enforcement damage report in your intel.";
		}
		announce(text, (broke || tacBomb) ? Misc.getNegativeHighlightColor()
				: Misc.getPositiveHighlightColor());
	}

	/** Called by the manager when the strike is defeated or driven off. */
	public void onStrikeDefeated() {
		if (strike == null) return;
		CommWarsConfig.log("Enforcement strike by " + factionId + " DEFEATED (escalation now "
				+ (escalation + 1) + ")");
		// a defeated enforcement action is a real setback: the bar resets low
		// and has to build all the way back up before they try again
		setProgress(Math.min(getProgress(), CommWarsConfig.strikeDefeatReset()));
		escalation++;

		// a broken coalition strike cracks the partners' resolve
		List<String> dropouts = new ArrayList<String>();
		if (strikePartners != null) {
			for (String partner : strikePartners) {
				if (Math.random() < CommWarsConfig.coalitionDropoutChance()) {
					CoalitionCalc.demoralize(partner);
					dropouts.add(partner);
					// washing their hands is not a pose: the quitter's own
					// resentment vents below the coalition threshold, so
					// rejoining takes the dropout timer AND months of fresh
					// anger - a defeat genuinely shrinks the next coalition
					GrievanceEventIntel quitter = get(partner);
					if (quitter != null) {
						quitter.setProgress(Math.min(quitter.getProgress(),
								CommWarsConfig.dropoutVentProgress()));
					}
				}
			}
		}
		// a faction that washes its hands leaves the roster NOW - not on the
		// next slow gate recompute, days after the announcement said it left
		coalitionPartners.removeAll(dropouts);
		clearStrike();

		String text = "The " + getFaction().getDisplayName() + " enforcement action has been "
				+ "defeated. Their resentment cools - but the next attempt will come in "
				+ "greater force.";
		if (!dropouts.isEmpty()) {
			text += " The defeat has shaken the coalition: " + getPartnerNames(dropouts)
					+ (dropouts.size() > 1 ? " abandon" : " abandons")
					+ " the coalition - their own grievances cool, though any quarrel of "
					+ "their own may yet outlive the alliance.";
		}
		announce(text, Misc.getPositiveHighlightColor());
	}


	/** Replace the cause snapshot and keep one commodity factor per active cause. */
	public void updateCauses(List<ShareTracker.Cause> list) {
		causes.clear();
		if (list != null) {
			for (ShareTracker.Cause c : list) {
				causes.put(c.commodityId, c);
			}
		}

		// remove factors for commodities no longer contested
		List<EventFactor> remove = new ArrayList<EventFactor>();
		for (EventFactor f : getFactors()) {
			if (f instanceof CommodityGrievanceFactor
					&& !causes.containsKey(((CommodityGrievanceFactor) f).getCommodityId())) {
				remove.add(f);
			}
		}
		for (EventFactor f : remove) {
			removeFactor(f);
		}

		// add factors for newly contested commodities
		Set<String> have = new LinkedHashSet<String>();
		for (EventFactor f : getFactors()) {
			if (f instanceof CommodityGrievanceFactor) {
				have.add(((CommodityGrievanceFactor) f).getCommodityId());
			}
		}
		for (String id : causes.keySet()) {
			if (!have.contains(id)) {
				addFactor(new CommodityGrievanceFactor(id));
			}
		}

		// keep the coalition-partners block present and last, so the partner
		// factor groups render beneath this faction's own factors
		EventFactor coalition = getFactorOfClass(CoalitionFactorsFactor.class);
		if (coalition == null) {
			coalition = new CoalitionFactorsFactor();
		} else {
			removeFactor(coalition);
		}
		addFactor(coalition);
	}

	/** Called every manager tick; ends the grievance after sustained total calm. */
	public void tickCalm() {
		if (vendetta) {
			calmSince = null;
			return; // blood feuds do not cool
		}
		boolean calm = !hasCauses() && getProgress() <= 0;
		if (!calm) {
			calmSince = null;
			return;
		}
		if (calmSince == null) {
			calmSince = Global.getSector().getClock().getTimestamp();
			return;
		}
		float days = Global.getSector().getClock().getElapsedDaysSince(calmSince);
		if (days >= CommWarsConfig.endAfterCalmDays()) {
			CommWarsConfig.log("Grievance with " + factionId + " has gone calm for "
					+ (int) days + " days - ending");
			endAfterDelay();
		}
	}

	public float daysCalm() {
		if (calmSince == null) return 0f;
		return Global.getSector().getClock().getElapsedDaysSince(calmSince);
	}

	@Override
	protected void notifyEnded() {
		super.notifyEnded();
		Global.getSector().getListenerManager().removeListener(this);
		Global.getSector().removeScript(this);
	}

	// ---- stage transitions ----

	/**
	 * Feed announcement with the faction's crest; click goes to this intel.
	 * Vanilla-style formatting: normal text color, with this faction's name
	 * (and any coalition partners mentioned) highlighted in faction colors.
	 * The color parameter is retained for call-site simplicity but no longer
	 * tints the whole message.
	 */
	public void announce(String text, Color color) {
		List<String> highlights = new ArrayList<String>();
		List<Color> colors = new ArrayList<Color>();

		highlights.add(getFaction().getDisplayName());
		colors.add(getFaction().getBaseUIColor());

		// color any partner faction names that appear in the text
		List<String> mentioned = new ArrayList<String>(coalitionPartners);
		if (strikePartners != null) {
			for (String id : strikePartners) {
				if (!mentioned.contains(id)) mentioned.add(id);
			}
		}
		for (String id : mentioned) {
			FactionAPI partner = Global.getSector().getFaction(id);
			if (partner == null) continue;
			if (text.contains(partner.getDisplayName())) {
				highlights.add(partner.getDisplayName());
				colors.add(partner.getBaseUIColor());
			}
		}

		MessageIntel msg = new MessageIntel(text, Misc.getTextColor(),
				highlights.toArray(new String[0]), colors.toArray(new Color[0]));
		String crest = getFaction().getCrest();
		if (crest != null) msg.setIcon(crest);
		Global.getSector().getCampaignUI().addMessage(msg, MessageClickAction.INTEL_TAB, this);
	}

	@Override
	protected void notifyStageReached(EventStageData stage) {
		String factionName = Misc.ucFirst(getFaction().getDisplayNameWithArticle());
		Color bad = Misc.getNegativeHighlightColor();

		if (vendetta && (stage.id == Stage.WARNING || stage.id == Stage.ULTIMATUM)) {
			// no diplomacy in a blood feud - the bar just climbs toward reprisal
			CommWarsConfig.log("Vendetta with " + factionId + " passed " + stage.id);
			return;
		}

		if (stage.id == Stage.WARNING) {
			CommWarsConfig.log("Grievance with " + factionId + " reached WARNING");
			String objection;
			if (militaryCause != null && causes.isEmpty()) {
				objection = "military buildup";
			} else if (militaryCause != null) {
				objection = "export practices and military buildup";
			} else {
				objection = "export practices";
			}
			announce(factionName + " formally objects to your polity's " + objection + ".", bad);
		} else if (stage.id == Stage.ULTIMATUM) {
			if (!emboldened) {
				// too weak to press demands; hold below the line
				setProgress(GATE_CAP);
				CommWarsConfig.log("Grievance with " + factionId
						+ " hit ULTIMATUM while gated - clamped");
				return;
			}
			CommWarsConfig.log("Grievance with " + factionId + " reached ULTIMATUM");
			String demand = !causes.isEmpty()
					? "scale back your exports of " + getContestedCommodityNames()
					: "scale back your military buildup";
			announce(factionName + " issues an ultimatum: " + demand
					+ ", or face enforcement measures."
					+ (isCoalitionBacked()
						? " The demand is backed by a coalition: "
								+ getPartnerNames(coalitionPartners) + "."
						: ""), bad);
		} else if (stage.id == Stage.ENFORCEMENT) {
			tryLaunchEnforcement();
		}
	}

	/**
	 * The single guarded path to launching a strike: gate, unresolved-strike
	 * cleanup, and the cooldown all get their say. Called when the bar
	 * reaches 600 and re-checked by the manager while it sits there (so a
	 * bar pinned at 600 during the cooldown fires the moment it elapses).
	 */
	public void tryLaunchEnforcement() {
		if (isEnding() || isEnded()) return;
		if (getProgress() < ENFORCEMENT_PROGRESS) return;

		String factionName = Misc.ucFirst(getFaction().getDisplayNameWithArticle());

		if (!emboldened) {
			setProgress(GATE_CAP);
			CommWarsConfig.log("Grievance with " + factionId
					+ " hit ENFORCEMENT while gated - clamped");
			return;
		}
		if (isDeferredToVanillaCrisis()) {
			CommWarsConfig.log("Grievance with " + factionId
					+ " at ENFORCEMENT but deferred to vanilla colony crisis");
			return;
		}
		if (isStrikeActive()) return;

		// a finished strike must be resolved into consequences before
		// anything new launches - otherwise a fast-refilling bar can
		// clobber the outcome (truce, theft, vent) before the manager
		// tick gets to it. Judged as a whole, like the manager does.
		if (strike != null) {
			if (!allStrikeRaidsResolved()) return; // joint op still unfolding
			if (anyStrikeRaidSucceeded()) {
				onStrikeSucceeded();
			} else {
				onStrikeDefeated();
			}
			// consequences (truce, vent) govern what happens next; don't
			// also launch a fresh strike in the same breath
			if (getProgress() >= ENFORCEMENT_PROGRESS) {
				setProgress(CommWarsConfig.strikeResetProgress());
			}
			return;
		}

		// boiling, but the fleets need time to reconstitute: the bar holds
		// at the brink until the cooldown elapses (manager re-checks)
		if (daysSinceLastStrikeEnd() < CommWarsConfig.strikeCooldownDays()) {
			CommWarsConfig.log("Grievance with " + factionId
					+ " at ENFORCEMENT but strike cooldown holds ("
					+ (int) daysSinceLastStrikeEnd() + "/"
					+ (int) CommWarsConfig.strikeCooldownDays() + " days)");
			return;
		}

		CommWarsConfig.log("Grievance with " + factionId + " reached ENFORCEMENT");
		boolean launched = EnforcementStrike.launch(this);
		if (launched) {
			String text;
			if (vendetta) {
				text = factionName + " has dispatched retribution fleets. Saturation "
						+ "bombardment of your worlds is their sole intent.";
			} else {
				text = factionName + " is assembling enforcement fleets to settle the "
						+ "dispute by force.";
			}
			if (isCoalitionBacked()) {
				text += " Coalition contingents join the operation: "
						+ getPartnerNames(coalitionPartners) + ".";
			}
			announce(text, Misc.getNegativeHighlightColor());
		}
		// launched or not, come off the boil; if no valid target existed
		// (e.g. no player colonies), settle just below the ultimatum line
		setProgress(launched ? CommWarsConfig.strikeResetProgress()
				: ULTIMATUM_PROGRESS - 50);
	}

	/**
	 * Phase 6: the player's own hostile acts against this faction feed the
	 * metre. Spikes are instant, can cross any threshold - including
	 * detonating an enforcement strike - and are still subject to the
	 * strength gate and cooldown like any other trigger.
	 */
	public void onPlayerRetaliation(int spike, String actDesc, MarketAPI market) {
		if (isEnding() || isEnded()) return;
		if (spike <= 0) return;
		int before = getProgress();
		CommWarsConfig.log("Player retaliation vs " + factionId + " (" + actDesc + " @ "
				+ market.getName() + "): +" + spike + " (bar " + before + " -> "
				+ Math.min(getMaxProgress(), before + spike) + ")");
		announce(Misc.ucFirst(getFaction().getDisplayNameWithArticle()) + " seethes at your "
				+ actDesc + " on " + market.getName() + " - resentment surges.",
				Misc.getNegativeHighlightColor());
		// through the event framework, not a bare setProgress: the spike lands
		// AND shows in the "Recent one-time factors" panel with the reason
		addFactor(new RetaliationFactor(spike,
				"Your " + actDesc + " on " + market.getName()));
	}

	// ---- presentation ----

	@Override
	public String getName() {
		if (vendetta) {
			return "Blood Feud - " + getFaction().getDisplayName();
		}
		String kind;
		if (isMilitaryDominant()) {
			kind = "Military Grievance";
		} else if (!causes.isEmpty()) {
			kind = "Trade Grievance";
		} else {
			// no active causes: cooling off toward expiry
			kind = "Grievance";
		}
		return kind + " - " + getFaction().getDisplayName();
	}

	@Override
	public String getIcon() {
		String crest = getFaction().getCrest();
		if (crest != null) return crest;
		return super.getIcon();
	}

	/**
	 * Dormant: nothing is actually happening - no active causes accruing, no
	 * strike, and resentment below the warning line (not even a diplomatic
	 * note). A grievance whose causes have lapsed and is simply decaying
	 * toward expiry. A vendetta (blood debt) is never dormant.
	 */
	public boolean isDormant() {
		if (isVendetta()) return false;
		return !hasCauses() && !isStrikeActive() && getProgress() < WARNING_PROGRESS;
	}

	/**
	 * This grievance is not currently pressing anything against the player -
	 * it is on hold, for any of the reasons a grievance goes quiet. It is
	 * hidden from the intel list so the list shows only disputes actually in
	 * motion. A grievance is paused when it is:
	 *   - merely backing another faction's coalition (that anchor's screen
	 *     already speaks for the whole bloc, and settling there settles this);
	 *   - too weak to press its demands, alone or pooled (the strength gate);
	 *   - quieted because you serve the faction's flag (commission);
	 *   - deferred to a vanilla colony crisis that speaks for it;
	 *   - holding a post-strike truce (the announcement message tells the
	 *     player the action ran its course; the entry returns when the truce
	 *     lapses and resentment resumes);
	 *   - committed to a coalition partner's active strike.
	 * An active strike of its own, a coalition it is itself leading, or a
	 * blood feud are never "paused" - those always show.
	 */
	public boolean isPaused() {
		if (isVendetta()) return false;          // a blood feud never rests
		if (isStrikeActive()) return false;      // its own strike is in progress
		if (isCoalitionBacked()) return false;   // it leads its own coalition
		if (!getBackedFactions().isEmpty()) return true;  // only backing another
		if (supportingStrikeFor != null) return true;     // in a partner's strike
		if (!emboldened) return true;            // too weak to press (the gate)
		if (isCommissionSuppressed()) return true;   // you serve their flag
		if (isDeferredToVanillaCrisis()) return true;// a vanilla crisis speaks for it
		if (isInTruce()) return true;            // holding fire after a strike
		return false;
	}

	/**
	 * Keep the intel entry out of the list when it is dormant (no active
	 * causes, winding down toward expiry) or paused for any reason (backing
	 * another's coalition, too weak to press, suppressed by a commission,
	 * deferred to a vanilla crisis, in truce). Only grievances actively in
	 * motion against the player stay on the list.
	 */
	@Override
	public boolean isHidden() {
		return super.isHidden() || isDormant() || isPaused();
	}

	@Override
	public Color getBarColor() {
		Color color = getFaction().getBaseUIColor();
		return Misc.interpolateColor(color, Color.black, 0.25f);
	}

	@Override
	public boolean isEventProgressANegativeThingForThePlayer() {
		return true;
	}

	@Override
	protected String getStageIconImpl(Object stageId) {
		EventStageData esd = getDataFor(stageId);
		if (esd == null) return super.getStageIconImpl(stageId);
		if (esd.id == Stage.START) {
			return Global.getSettings().getSpriteName("events", "hostile_activity_START");
		}
		if (esd.id == Stage.ENFORCEMENT) {
			return Global.getSettings().getSpriteName("events", "hostile_activity");
		}
		String crest = getFaction().getCrest();
		if (crest != null) return crest;
		return super.getStageIconImpl(stageId);
	}

	@Override
	protected void addBulletPoints(TooltipMakerAPI info, ListInfoMode mode, boolean isUpdate,
								   Color tc, float initPad) {
		if (addEventFactorBulletPoints(info, mode, isUpdate, tc, initPad)) {
			return;
		}
		Color h = Misc.getHighlightColor();
		FactionAPI faction = getFaction();

		info.addPara("Faction: %s", initPad, tc, faction.getBaseUIColor(), faction.getDisplayName());

		// the full coalition roster, right after the aggrieved faction: every
		// faction pressing this dispute, each named in its own faction colour
		if (isCoalitionBacked()) {
			addFactionListPara(info, "In coalition with", coalitionPartners, 0f);
		}
		List<String> backed = getBackedFactions();
		if (!backed.isEmpty()) {
			addFactionListPara(info, "Backing the grievances of", backed, 0f);
		}

		if (!causes.isEmpty()) {
			info.addPara("Contested exports: %s", 0f, tc, h, getContestedCommodityNames());
		}
		if (militaryCause != null) {
			info.addPara("Contested: %s", 0f, tc, h, "military buildup");
		}
		if (isInTruce()) {
			info.addPara("Truce: %s days remain", 0f, tc, h, "" + (int) getTruceDaysLeft());
		}
	}

	public String getContestedCommodityNames() {
		List<String> names = new ArrayList<String>();
		for (String id : causes.keySet()) {
			try {
				names.add(Global.getSettings().getCommoditySpec(id).getName().toLowerCase());
			} catch (Throwable t) {
				names.add(id);
			}
		}
		return Misc.getAndJoined(names.toArray(new String[0]));
	}

	/**
	 * The narrative stage shown in the description panel - only one stage's
	 * text is displayed at a time, reflecting where the dispute actually
	 * stands rather than listing every possible future.
	 */
	protected Object getDisplayStage() {
		if (isStrikeActive()) return Stage.ENFORCEMENT;
		// the standing ultimatum lapses once resentment is bought or cooled
		// down below the warning line - the narrative de-escalates with it
		if (isUltimatumReached() && hasCauses() && emboldened
				&& getProgress() >= WARNING_PROGRESS) {
			return Stage.ULTIMATUM;
		}
		if (getProgress() >= WARNING_PROGRESS) return Stage.WARNING;
		return Stage.START;
	}

	@Override
	public void addStageDescriptionWithImage(TooltipMakerAPI main, Object stageId) {
		if (!stageId.equals(getDisplayStage())) return;
		super.addStageDescriptionWithImage(main, stageId);
	}

	@Override
	public void addStageDescriptionText(TooltipMakerAPI info, float width, Object stageId) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		FactionAPI faction = getFaction();
		String factionName = faction.getDisplayNameWithArticle();
		String factionNameUc = Misc.ucFirst(factionName);
		boolean plural = "are".equals(faction.getDisplayNameIsOrAre());
		String hasOrHave = faction.getDisplayNameHasOrHave();
		String isOrAre = faction.getDisplayNameIsOrAre();

		if (vendetta) {
			if (isStrikeActive()) {
				info.addPara("Retribution fleets are underway. Saturation bombardment of your "
						+ "worlds is their sole intent.", opad);
			} else {
				info.addPara(factionNameUc + " " + (plural ? "remember" : "remembers")
						+ " what you burned. There will be no notes, no ultimatums, no "
						+ "settlements - only retribution, whenever and however "
						+ (plural ? "they" : "it") + " " + isOrAre + " able. The feud ends "
						+ "when one side has no worlds left.", opad);
				if (!emboldened) {
					info.addPara("Broken as they are, the means are gone - but hatred outlives "
							+ "means. Should they rebuild, they will come.", opad,
							Misc.getPositiveHighlightColor(), h, "");
				}
			}
			if (escalation > 0) {
				info.addPara("Reprisals repelled so far: %s. Each defeat only deepens the debt.",
						opad, h, "" + escalation);
			}
			return;
		}

		boolean trade = !causes.isEmpty();
		boolean military = militaryCause != null;

		if (stageId == Stage.START) {
			if (military && !trade) {
				info.addPara(factionNameUc + " " + hasOrHave + " taken note of your polity's "
						+ "growing arsenal - command structures, battlestations, war industry. "
						+ "For now, the alarm is confined to defense ministries and staff "
						+ "briefings - but it is being tallied.", opad);
			} else {
				info.addPara(factionNameUc + " " + hasOrHave + " taken note of your polity's "
						+ "growing dominance in markets "
						+ (plural ? "they consider their" : "it considers its")
						+ " own. For now, the resentment simmers in trade ministries and "
						+ "merchant guild halls - but it is being tallied.", opad);
				if (military) {
					info.addPara("It is not only trade: your growing arsenal is being watched "
							+ "with equal unease.", opad);
				}
			}
			if (trade) {
				info.addPara("Currently contested: %s.", opad, h, getContestedCommodityNames());
			}
		} else if (stageId == Stage.WARNING) {
			String objection;
			if (military && !trade) {
				objection = "your polity's military buildup and \""
						+ (plural ? "reserve" : "reserves")
						+ " the right to safeguard the regional balance of power.\"";
			} else if (trade && military) {
				objection = "your polity's export practices and military buildup, and \""
						+ (plural ? "reserve" : "reserves") + " the right to protect "
						+ (plural ? "their" : "its") + " legitimate interests.\"";
			} else {
				objection = "your polity's export practices and \""
						+ (plural ? "reserve" : "reserves") + " the right to protect "
						+ (plural ? "their" : "its") + " legitimate commercial interests.\"";
			}
			info.addPara("A diplomatic note arrives: " + factionName + " formally "
					+ (plural ? "object" : "objects") + " to " + objection
					+ " There is no explicit demand yet - this is a warning shot.", opad);
		} else if (stageId == Stage.ULTIMATUM) {
			String demand;
			if (military && !trade) {
				demand = "scale back your military infrastructure";
			} else if (trade && military) {
				demand = "scale back your exports of the contested commodities and your "
						+ "military infrastructure";
			} else {
				demand = "scale back your exports of the contested commodities";
			}
			info.addPara(factionNameUc + " " + (plural ? "issue" : "issues") + " a formal "
					+ "ultimatum: " + demand + ", or face enforcement measures.", opad);

			if (military && !trade) {
				info.addPara("Compliance is judged by results, not promises: their alarm eases "
						+ "only once your military-industrial capability actually shrinks - "
						+ "downgrade or demolish the offending structures. Alternatively, "
						+ "one-off settlement payments can buy down accumulated resentment... "
						+ "though the dispute itself remains as long as your arsenal does.", opad);
			} else {
				info.addPara("Compliance is judged by results, not promises: resentment cools "
						+ "only once your sector market share actually falls - downgrade or "
						+ "demolish the producing industries, or lose the share some other way. "
						+ "Alternatively, one-off settlement payments can buy down accumulated "
						+ "resentment... though the dispute itself remains as long as your "
						+ "dominance does.", opad);
			}
			if (trade) {
				info.addPara("Currently contested: %s.", opad, h, getContestedCommodityNames());
			}
			if (military) {
				info.addPara((trade ? "They further demand you scale back your military "
						+ "infrastructure - or place" : "Alternatively, you may place")
						+ " your arsenal under their authority by taking a commission with "
						+ (plural ? "them" : "it") + ".", opad);
			}
			if (isCoalitionBacked()) {
				addColoredFactionPara(info, "Too weak to press this alone, they act in coalition "
						+ "with " + getPartnerNames(coalitionPartners) + ". The pooled strength is "
						+ "what makes the ultimatum credible - break the coalition, and the demands "
						+ "may die with it.", opad);
			} else if (!emboldened) {
				info.addPara("They lack the strength to press this any further - even counting "
						+ "every ally they could rally. The grievance simmers, held below the "
						+ "point of ultimatum by your military superiority.", opad,
						Misc.getPositiveHighlightColor(), h, "");
			}
		} else if (stageId == Stage.ENFORCEMENT) {
			String objective;
			if (strikeHeistIndustryId != null) {
				objective = "breach " + (strikeTarget != null ? strikeTarget.getName()
						+ "'s" : "your") + " defenses and seize strategic equipment";
			} else if (military && !trade) {
				objective = "strike your military infrastructure directly";
			} else if (trade && military) {
				objective = "raid your stockpiles and cripple the offending industries - "
						+ "commercial and military alike";
			} else {
				objective = "raid your stockpiles and disrupt the offending industries";
			}
			addColoredFactionPara(info, "Words have run out. " + factionNameUc + " " + isOrAre
					+ " sending enforcement fleets to " + objective + "."
					+ (strikePartners != null && !strikePartners.isEmpty()
						? " Coalition contingents from " + getPartnerNames(strikePartners)
								+ " sail with them."
						: ""), opad);
			// raids adapt on the ground; warn when their orders extend to shells
			if (!vendetta && strikeHeistIndustryId == null
					&& escalation >= CommWarsConfig.tacBombEscalation()) {
				info.addPara("Their commanders hold bombardment authority: should they find "
						+ "the offending industries already silent, the shells fall instead.",
						opad, Misc.getNegativeHighlightColor(),
						Misc.getHighlightColor(), "bombardment authority");
			}
			// ground assessment, in this properly-wrapped column (only shows
			// once the raiders are close enough to measure)
			if (isStrikeActive()) addGroundAssessment(info);
		}
	}

	@Override
	public void afterStageDescriptions(TooltipMakerAPI main) {
		// Everything the base class lets us add here spans the full intel panel,
		// which is wider than the bar/description column above it - so this text
		// ran clear across the page. Add it inside a sub-tooltip constrained to
		// the same column width, so it wraps in line with the rest.
		float w = getBarWidth();

		boolean coalitionText = isCoalitionBacked() && getDisplayStage() != Stage.ULTIMATUM;
		List<String> backedByThis = getBackedFactions();
		boolean enforcementText = !canRespond() && isUltimatumReached() && isStrikeActive();

		if (coalitionText || !backedByThis.isEmpty() || enforcementText) {
			TooltipMakerAPI col = main.beginSubTooltip(w);
			if (coalitionText) {
				addColoredFactionPara(col, Misc.ucFirst(getFaction().getDisplayNameWithArticle())
						+ " lacks the strength to press this alone, and does so in coalition with "
						+ getPartnerNames(coalitionPartners) + " - their pooled might is what makes "
						+ "the demands credible.", 10f);
			}
			if (!backedByThis.isEmpty()) {
				addColoredFactionPara(col, Misc.ucFirst(getFaction().getDisplayNameWithArticle())
						+ " is also lending its strength to the grievances of "
						+ getPartnerNames(backedByThis) + " - without its backing, their demands "
						+ "would collapse.", 10f);
			}
			if (enforcementText) {
				col.addPara("Enforcement fleets are underway - the time for negotiation has passed.",
						Misc.getNegativeHighlightColor(), 10f);
			}
			main.endSubTooltip();
			main.addCustom(col, 10f);
		}

		// the button stays on the full panel so its click routing is unaffected
		if (canRespond()) {
			main.addSpacer(10f);
			main.addButton("Respond to the ultimatum", BUTTON_ORDERS, 220f, 20f, 10f);
		}

		if (!CommWarsConfig.debugMode()) return;

		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color n = Misc.getNegativeHighlightColor();

		TooltipMakerAPI info = main.beginSubTooltip(w);

		info.addSectionHeading("Debug", n, Misc.getDarkPlayerColor(), Alignment.MID, opad * 2f);

		info.addPara("faction: %s | progress: %s / %s | monthly: %s | clockMult: %s", opad, h,
				factionId,
				"" + getProgress(), "" + getMaxProgress(),
				"" + getMonthlyProgress(),
				"" + CommWarsConfig.clockMult());

		info.addPara("thresholds: warning %s, ultimatum %s, enforcement %s | decay/mo: %s | "
				+ "calm: %s / %s days", 3f, h,
				"" + WARNING_PROGRESS, "" + ULTIMATUM_PROGRESS, "" + ENFORCEMENT_PROGRESS,
				"" + CommWarsConfig.decayPerMonth(),
				"" + (int) daysCalm(), "" + (int) CommWarsConfig.endAfterCalmDays());

		if (causes.isEmpty() && militaryCause == null) {
			info.addPara("causes: none", 3f);
		}
		for (ShareTracker.Cause c : causes.values()) {
			info.addPara("cause: %s | player %s vs faction %s | weight %s (cap %s)", 3f, h,
					c.commodityId,
					c.playerShare + "%", c.factionShare + "%",
					Misc.getRoundedValueMaxOneAfterDecimal(c.weight),
					"" + CommWarsConfig.maxMonthlyPerCommodity());
		}
		if (militaryCause != null) {
			info.addPara("cause: MILITARY | player %s vs faction %s | weight %s (cap %s) | "
					+ "milDominant %s", 3f, h,
					"" + (int) militaryCause.playerScore, "" + (int) militaryCause.factionScore,
					Misc.getRoundedValueMaxOneAfterDecimal(militaryCause.weight),
					"" + CommWarsConfig.maxMonthlyMilitary(),
					"" + isMilitaryDominant());
		}
		info.addPara("commissioned with: %s (was %s)", 3f, h,
				"" + Misc.getCommissionFactionId(), "" + wasCommissioned);

		info.addPara("detection: minPlayerShare %s | minFactionShare %s | topProducers %s | "
				+ "noticeFraction %s | weightMult %s", 3f, h,
				"" + CommWarsConfig.minPlayerShare(), "" + CommWarsConfig.minFactionShare(),
				"" + CommWarsConfig.topProducers(),
				"" + CommWarsConfig.noticeFraction(), "" + CommWarsConfig.weightMult());

		info.addPara("mil detection: milMinPlayerScore %s | milNoticeFraction %s (need faction "
				+ "score <= %s) | milWeightMult %s", 3f, h,
				"" + CommWarsConfig.milMinPlayerScore(),
				"" + CommWarsConfig.milNoticeFraction(),
				"" + (int) (MilitaryScore.playerScore() / Math.max(0.01f, CommWarsConfig.milNoticeFraction())),
				"" + CommWarsConfig.milWeightMult());

		info.addPara("escalation: %s | settle cost/point: %s (full %s, paid total %s) | "
				+ "truce left: %s | suppressed: %s", 3f, h,
				"" + escalation,
				Misc.getRoundedValueMaxOneAfterDecimal(TributeCalc.costPerPoint(this)),
				"" + TributeCalc.costFor(this, getProgress()), "" + totalTributePaid,
				"" + (int) getTruceDaysLeft(),
				"" + isAccrualSuppressed());

		String strikeState = "none";
		if (strike != null) {
			strikeState = (strikeHeistIndustryId != null ? "HEIST(" + strikeHeistIndustryId + ")"
						: strikeWasTacBomb ? "tacbomb" : "raid")
					+ " vs " + (strikeTarget != null ? strikeTarget.getName() : "?")
					+ (strike.isSucceeded() ? " (succeeded)"
						: strike.isAborted() || strike.isFailed() ? " (defeated)" : " (active)");
		}
		info.addPara("strike: %s | ultimatumReached: %s | supporting: %s | vanillaCrisis: %s | "
				+ "vendetta: %s", 3f, h,
				strikeState, "" + isUltimatumReached(),
				supportingStrikeFor == null ? "none" : supportingStrikeFor,
				"" + isDeferredToVanillaCrisis(),
				"" + vendetta);

		info.addPara("faction milScore: %s (capacity %s) | player milScore: %s", 3f, h,
				"" + (int) MilitaryScore.factionScore(getFaction()),
				"" + (int) (MilitaryScore.factionScore(getFaction()) * CommWarsConfig.capacityMult()),
				"" + (int) MilitaryScore.playerScore());

		info.addPara("gate: emboldened %s | pooled %s vs threshold %s | partners: %s | "
				+ "demoralized: %s", 3f, h,
				"" + emboldened,
				"" + (int) CoalitionCalc.pooledScore(factionId, coalitionPartners),
				"" + (int) CoalitionCalc.gateThreshold(),
				coalitionPartners.isEmpty() ? "none" : coalitionPartners.toString(),
				CoalitionCalc.describeDemoralized());

		String heistPreview;
		if (getEscalation() < CommWarsConfig.heistEscalation()) {
			heistPreview = "blocked: escalation " + getEscalation() + " < "
					+ CommWarsConfig.heistEscalation();
		} else {
			EnforcementStrike.HeistPlan plan = EnforcementStrike.planHeist(
					this, isMilitaryDominant(), getCauseCommodityIds());
			if (plan != null) {
				heistPreview = "item found: " + plan.industryId + " @ " + plan.market.getName()
						+ " - lifts only on raidStr >= defenderStr x "
						+ CommWarsConfig.heistBreakMargin();
			} else {
				heistPreview = "no stealable item in a relevant industry";
			}
		}
		info.addPara("heist next strike: %s", 3f, h, heistPreview);
		if (lastStrikeMath != null) {
			info.addPara("last strike: %s", 3f, h, lastStrikeMath);
		}

		main.endSubTooltip();
		main.addCustom(info, opad);
	}

	@Override
	public void buttonPressConfirmed(Object buttonId, IntelUIAPI ui) {
		if (BUTTON_ORDERS.equals(buttonId)) {
			if (canRespond()) {
				ui.showDialog(null, new GrievanceOrdersDialog(this, ui));
			}
			return;
		}
		super.buttonPressConfirmed(buttonId, ui);
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = super.getIntelTags(map);
		tags.add(factionId);
		return tags;
	}
}
