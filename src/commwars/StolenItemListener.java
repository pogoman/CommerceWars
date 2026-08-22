package commwars;

import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
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
		reportRetaliation(market, CommWarsConfig.retalSatBombSpike(), "atrocity");
		atrocityRipple(market);
	}

	/** Phase 6: the player's hostile acts feed the target faction's metre. */
	protected void reportRetaliation(MarketAPI market, int spike, String actDesc) {
		if (spike <= 0 || market == null) return;
		GrievanceEventIntel intel = GrievanceEventIntel.get(market.getFactionId());
		if (intel == null) return;
		intel.onPlayerRetaliation(spike, actDesc, market);
	}

	/**
	 * Saturation bombardment echoes across every other active grievance:
	 * factions strong enough to act grow hotter ("we're next - act now"),
	 * while those too weak are cowed out of coalition pools by terror.
	 */
	protected void atrocityRipple(MarketAPI bombed) {
		for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin p
				: Global.getSector().getIntelManager().getIntel(GrievanceEventIntel.class)) {
			GrievanceEventIntel other = (GrievanceEventIntel) p;
			if (other.isEnding() || other.isEnded()) continue;
			if (other.getFactionId().equals(bombed.getFactionId())) continue;

			if (other.isEmboldened()) {
				other.onPlayerRetaliation(CommWarsConfig.atrocityPeerSpike(),
						"atrocity against " + bombed.getFaction().getDisplayName(), bombed);
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
