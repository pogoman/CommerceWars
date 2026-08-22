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
	}

	@Override
	public void reportTacticalBombardmentFinished(InteractionDialogAPI dialog, MarketAPI market,
			TempData actionData) {
	}

	@Override
	public void reportSaturationBombardmentFinished(InteractionDialogAPI dialog, MarketAPI market,
			TempData actionData) {
	}
}
