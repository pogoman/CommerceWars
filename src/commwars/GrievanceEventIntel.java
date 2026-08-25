package commwars;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.comm.CommMessageAPI.MessageClickAction;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
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
	protected MarketAPI strikeTarget = null;
	protected MarketAPI strikeSource = null;
	protected List<String> strikeCommodities = null;
	protected boolean strikeWasTacBomb = false;
	protected boolean strikeLootTaken = false;
	protected String strikeHeistIndustryId = null;

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
			setProgress(Math.min(getMaxProgress(),
					getProgress() + CommWarsConfig.commissionLapseSpike()));
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
				|| isDeferredToVanillaCrisis();
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

	public boolean isStrikeActive() {
		return strike != null && !strike.isEnded() && !strike.isEnding();
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
		this.strikeLootTaken = false;
		this.strikeHeistIndustryId = heistIndustryId;
		this.strikePartners = new ArrayList<String>(coalitionPartners);
	}

	public void clearStrike() {
		releasePartners(false); // no-op for partners already released with a truce
		strike = null;
		strikeTarget = null;
		strikeSource = null;
		strikeCommodities = null;
		strikeWasTacBomb = false;
		strikeLootTaken = false;
		strikeHeistIndustryId = null;
		strikePartners = null;
		lastStrikeEnd = Global.getSector().getClock().getTimestamp();
	}

	public float daysSinceLastStrikeEnd() {
		if (lastStrikeEnd == null) return Float.MAX_VALUE;
		return Global.getSector().getClock().getElapsedDaysSince(lastStrikeEnd);
	}

	/**
	 * Fires the theft as soon as the strike's raid actually connects with the
	 * colony (success fraction goes positive), instead of waiting for the
	 * fleets to finish their whole operation and head home. Called every
	 * manager tick while a strike is active.
	 */
	public void checkStrikeRaidHit() {
		if (strike == null || strikeLootTaken) return;
		if (strike.isAborted() || strike.isFailed()) return;
		if (strike.getRaidAction() == null) return;
		if (strike.getRaidAction().getSuccessFraction() <= 0f) return;

		String loot = performTheft();
		if (loot != null) {
			announce(Misc.ucFirst(getFaction().getDisplayNameWithArticle())
					+ " raiders have carried off part of "
					+ (strikeTarget != null ? strikeTarget.getName() : "your colony")
					+ "'s stockpiles: " + loot
					+ (strikeSource != null ? " - bound for " + strikeSource.getName() + "." : "."),
					Misc.getNegativeHighlightColor());
		}
		performItemTheft();
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
	 * Carry off part of the contested goods from the target's resource
	 * stockpiles and storage; loot lands in the source market's open market,
	 * where it can be seen - and bought back. Returns a summary of what was
	 * taken, or null if there was nothing to take.
	 */
	protected String performTheft() {
		if (strikeTarget == null || strikeCommodities == null) return null;
		strikeLootTaken = true;

		CargoAPI destination = null;
		if (strikeSource != null) {
			SubmarketAPI open = strikeSource.getSubmarket(Submarkets.SUBMARKET_OPEN);
			if (open != null) destination = open.getCargo();
		}

		StringBuilder summary = new StringBuilder();
		for (String commodityId : strikeCommodities) {
			int taken = 0;
			for (String submarketId : new String[] {
					Submarkets.LOCAL_RESOURCES, Submarkets.SUBMARKET_STORAGE }) {
				SubmarketAPI sub = strikeTarget.getSubmarket(submarketId);
				CargoAPI cargo = sub == null ? null : sub.getCargo();
				if (cargo == null) {
					CommWarsConfig.log("  theft: " + submarketId + " missing on "
							+ strikeTarget.getName());
					continue;
				}
				float have = cargo.getCommodityQuantity(commodityId);
				int take = (int) (have * CommWarsConfig.stealFraction());
				CommWarsConfig.log("  theft: " + submarketId + " " + commodityId
						+ " have " + (int) have + ", taking " + take);
				if (take >= 1) {
					cargo.removeCommodity(commodityId, take);
					taken += take;
				}
			}
			if (taken > 0) {
				if (destination != null) destination.addCommodity(commodityId, taken);
				// real market effect: plundered goods depress local availability
				// for a while (on top of the raid's industry disruption)
				try {
					strikeTarget.getCommodityData(commodityId).getAvailableStat()
							.addTemporaryModFlat(CommWarsConfig.plunderDays(),
									"commwars_plunder", "Plundered stockpiles",
									-CommWarsConfig.plunderPenalty());
				} catch (Throwable t) {
					CommWarsConfig.log("  plunder malus failed for " + commodityId + ": " + t);
				}
				if (summary.length() > 0) summary.append(", ");
				summary.append(Misc.getWithDGS(taken)).append(" ");
				try {
					summary.append(Global.getSettings()
							.getCommoditySpec(commodityId).getName().toLowerCase());
				} catch (Throwable t) {
					summary.append(commodityId);
				}
			}
		}
		return summary.length() > 0 ? summary.toString() : null;
	}

	/** Called by the manager when the strike ends with the raiders victorious. */
	public void onStrikeSucceeded() {
		if (strike == null) return;
		CommWarsConfig.log("Enforcement strike by " + factionId + " on "
				+ (strikeTarget != null ? strikeTarget.getName() : "?") + " SUCCEEDED");

		// they made their point: accrual freezes for a while - for the whole
		// bloc. Except in a blood feud: there are no points, only reprisals
		if (!vendetta) {
			truceStart = Global.getSector().getClock().getTimestamp();
			truceDays = CommWarsConfig.truceDaysAfterStrike();
			releasePartners(true);
		} else {
			releasePartners(false);
		}

		// normally the theft fired when the raid connected (checkStrikeRaidHit);
		// this is the fallback in case resolution arrives first
		String lootSummary = strikeLootTaken ? null : performTheft();

		String targetName = strikeTarget != null ? strikeTarget.getName() : "your colony";
		String sourceName = strikeSource != null ? strikeSource.getName() : null;
		clearStrike();

		String text;
		if (vendetta) {
			text = Misc.ucFirst(getFaction().getDisplayNameWithArticle())
					+ "'s retribution fleets have done their work at " + targetName + ". "
					+ "The blood debt is not settled - it is merely fed.";
		} else {
			text = Misc.ucFirst(getFaction().getDisplayNameWithArticle())
					+ " enforcement action against " + targetName + " has run its course.";
			if (lootSummary != null) {
				text += " Seized from local stockpiles: " + lootSummary
						+ (sourceName != null ? " - carried off to " + sourceName + "." : ".");
			}
			text += " A grudging commercial truce takes hold - for now.";
		}
		announce(text, Misc.getNegativeHighlightColor());
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
				}
			}
		}
		clearStrike();

		String text = "The " + getFaction().getDisplayName() + " enforcement action has been "
				+ "defeated. Their resentment cools - but the next attempt will come in "
				+ "greater force.";
		if (!dropouts.isEmpty()) {
			text += " The defeat has shaken the coalition: " + getPartnerNames(dropouts)
					+ (dropouts.size() > 1 ? " wash their hands" : " washes its hands")
					+ " of the dispute.";
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
		// tick gets to it
		if (strike != null) {
			if (strike.isSucceeded()) {
				onStrikeSucceeded();
			} else if (strike.isAborted() || strike.isFailed()) {
				onStrikeDefeated();
			} else {
				clearStrike();
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
		setProgress(before + spike);
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
	 * Hide the intel entry while it is deferred to a vanilla colony crisis:
	 * the crisis intel is already in the list saying the same thing, and a
	 * frozen grievance is not a happening event. It stays registered and
	 * updated (getIntel still returns it), so it reappears the moment the
	 * crisis clears.
	 */
	@Override
	public boolean isHidden() {
		return super.isHidden() || isDeferredToVanillaCrisis();
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

		if (!causes.isEmpty()) {
			info.addPara("Contested exports: %s", 0f, tc, h, getContestedCommodityNames());
		}
		if (militaryCause != null) {
			info.addPara("Contested: %s", 0f, tc, h, "military buildup");
		}
		if (isCoalitionBacked()) {
			info.addPara("In coalition with: %s", 0f, tc, h, getPartnerNames(coalitionPartners));
		}
		List<String> backed = getBackedFactions();
		if (!backed.isEmpty()) {
			info.addPara("Backing the grievances of: %s", 0f, tc, h, getPartnerNames(backed));
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
				info.addPara("Too weak to press this alone, they act in coalition with %s. The "
						+ "pooled strength is what makes the ultimatum credible - break the "
						+ "coalition, and the demands may die with it.", opad, h,
						getPartnerNames(coalitionPartners));
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
			info.addPara("Words have run out. " + factionNameUc + " " + isOrAre + " sending "
					+ "enforcement fleets to " + objective + "."
					+ (strikePartners != null && !strikePartners.isEmpty()
						? " Coalition contingents from " + getPartnerNames(strikePartners)
								+ " sail with them."
						: ""), opad);
		}
	}

	@Override
	public void afterStageDescriptions(TooltipMakerAPI info) {
		if (isCoalitionBacked() && getDisplayStage() != Stage.ULTIMATUM) {
			info.addPara(Misc.ucFirst(getFaction().getDisplayNameWithArticle())
					+ " lacks the strength to press this alone, and does so in coalition "
					+ "with %s - their pooled might is what makes the demands credible.", 10f,
					Misc.getNegativeHighlightColor(), Misc.getHighlightColor(),
					getPartnerNames(coalitionPartners));
		}
		List<String> backedByThis = getBackedFactions();
		if (!backedByThis.isEmpty()) {
			info.addPara(Misc.ucFirst(getFaction().getDisplayNameWithArticle())
					+ " is also lending its strength to the grievances of %s - without its "
					+ "backing, their demands would collapse.", 10f,
					Misc.getNegativeHighlightColor(), Misc.getHighlightColor(),
					getPartnerNames(backedByThis));
		}
		if (canRespond()) {
			info.addSpacer(10f);
			info.addButton("Respond to the ultimatum", BUTTON_ORDERS, 220f, 20f, 10f);
		} else if (isUltimatumReached() && isStrikeActive()) {
			info.addPara("Enforcement fleets are underway - the time for negotiation has passed.",
					Misc.getNegativeHighlightColor(), 10f);
		}

		if (!CommWarsConfig.debugMode()) return;

		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color n = Misc.getNegativeHighlightColor();

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
				heistPreview = "READY: " + plan.industryId + " @ " + plan.market.getName()
						+ " | marines " + (int) plan.capacity + "/" + (int) plan.neededMarines;
			} else {
				heistPreview = "no viable plan (marine capacity "
						+ (int) MilitaryScore.marineCapacity(getFaction())
						+ ", or no stealable item in relevant industries)";
			}
		}
		info.addPara("heist next strike: %s", 3f, h, heistPreview);
		if (lastStrikeMath != null) {
			info.addPara("last strike: %s", 3f, h, lastStrikeMath);
		}
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
