package commwars;

import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.ColonyPlayerHostileActListener;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.TempData;
import com.fs.starfarer.api.util.Misc;

/**
 * Steal-back hook: when the player raids a market that holds items stolen by
 * enforcement heists, the items are recovered as part of the raid loot.
 * Registered transient by the mod plugin on every game load.
 */
public class StolenItemListener implements ColonyPlayerHostileActListener {

	@Override
	public void reportRaidForValuablesFinishedBeforeCargoShown(InteractionDialogAPI dialog,
			MarketAPI market, TempData actionData, CargoAPI cargo) {
		List<StolenItems.Record> records = StolenItems.forMarket(market.getId());
		for (StolenItems.Record r : records) {
			// pull it out of the industry it was re-installed into, if still there
			if (r.industryId != null) {
				Industry ind = market.getIndustry(r.industryId);
				if (ind != null && ind.getSpecialItem() != null
						&& ind.getSpecialItem().getId().equals(r.itemId)) {
					ind.setSpecialItem(null);
				}
			}
			cargo.addSpecial(new SpecialItemData(r.itemId, r.itemData), 1);
			StolenItems.remove(r);

			String name = r.itemId;
			try {
				name = Global.getSettings().getSpecialItemSpec(r.itemId).getName();
			} catch (Throwable t) {
			}
			if (dialog != null && dialog.getTextPanel() != null) {
				dialog.getTextPanel().addPara("Your marines recover the " + name
						+ " stolen from " + r.origMarketName + "!",
						Misc.getPositiveHighlightColor());
			}
			CommWarsConfig.log("Player recovered stolen item " + r.itemId
					+ " from " + market.getName());
		}
	}

	@Override
	public void reportRaidToDisruptFinished(InteractionDialogAPI dialog, MarketAPI market,
			TempData actionData, Industry industry) {
		reportRetaliation(market, CommWarsConfig.retalRaidSpike(), "raid");
	}

	@Override
	public void reportTacticalBombardmentFinished(InteractionDialogAPI dialog, MarketAPI market,
			TempData actionData) {
		reportRetaliation(market, CommWarsConfig.retalTacBombSpike(), "bombardment");
	}

	@Override
	public void reportSaturationBombardmentFinished(InteractionDialogAPI dialog, MarketAPI market,
			TempData actionData) {
		// the colony may already be decivilized by the time this fires, with
		// ownership reverted to Neutral - resolve the true victim from the
		// bombardment's own hostility list (its first entry is always the
		// owner at bombardment time), falling back to the market faction
		FactionAPI victim = null;
		if (actionData != null && actionData.willBecomeHostile != null
				&& !actionData.willBecomeHostile.isEmpty()) {
			victim = actionData.willBecomeHostile.get(0);
		}
		if (victim == null && market != null) victim = market.getFaction();

		// no societal grievance without a societal victim: nothing meaningful
		// was bombed (neutral ghost market), or the victim is a hidden menace
		// faction - Threat, Remnants and kin (showInIntelTab false) - whose
		// extermination the sector does not mourn
		if (victim == null || victim.isNeutralFaction() || !victim.isShowInIntelTab()) return;

		// convert, don't stack: sat-bombing a faction starts (or deepens) a
		// blood feud rather than spiking the ordinary grievance
		if (CommWarsConfig.vendettaEnabled() && !victim.isPlayerFaction()) {
			GrievanceEventIntel intel = GrievanceEventIntel.get(victim.getId());
			if (intel == null) {
				intel = new GrievanceEventIntel(victim.getId(), null);
			}
			intel.declareVendetta(market);
		} else {
			reportRetaliation(victim.getId(), CommWarsConfig.retalSatBombSpike(), "atrocity", market);
		}
		atrocityRipple(market, victim);
	}

	/** Phase 6: the player's hostile acts feed the target faction's metre. */
	protected void reportRetaliation(MarketAPI market, int spike, String actDesc) {
		if (market == null) return;
		reportRetaliation(market.getFactionId(), spike, actDesc, market);
	}

	protected void reportRetaliation(String factionId, int spike, String actDesc, MarketAPI market) {
		if (spike <= 0 || factionId == null) return;
		GrievanceEventIntel intel = GrievanceEventIntel.get(factionId);
		if (intel == null) return;
		intel.onPlayerRetaliation(spike, actDesc, market);
	}

	/**
	 * Saturation bombardment echoes across every other active grievance:
	 * factions strong enough to act grow hotter ("we're next - act now"),
	 * while those too weak are cowed out of coalition pools by terror.
	 */
	protected void atrocityRipple(MarketAPI bombed, FactionAPI victim) {
		for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin p
				: Global.getSector().getIntelManager().getIntel(GrievanceEventIntel.class)) {
			GrievanceEventIntel other = (GrievanceEventIntel) p;
			if (other.isEnding() || other.isEnded()) continue;
			if (other.getFactionId().equals(victim.getId())) continue;

			if (other.isEmboldened()) {
				other.onPlayerRetaliation(CommWarsConfig.atrocityPeerSpike(),
						"atrocity against " + victim.getDisplayName(), bombed);
			} else {
				CoalitionCalc.demoralize(other.getFactionId());
				other.announce(Misc.ucFirst(other.getFaction().getDisplayNameWithArticle())
						+ " is cowed by the atrocity at " + bombed.getName()
						+ " - too weak to answer it, and unwilling to provoke the same fate.",
						Misc.getPositiveHighlightColor());
				CommWarsConfig.log("Atrocity cowed " + other.getFactionId()
						+ " (demoralized out of coalition pools)");
			}
		}
	}
}
