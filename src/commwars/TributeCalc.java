package commwars;

import com.fs.starfarer.api.campaign.econ.CommodityMarketDataAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketShareDataAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * Settlement math: one-off reparation payments buy down accumulated
 * resentment. A full settlement from max resentment costs a configurable
 * number of months of the player's contested export income (with a floor),
 * and partial payments price per point off the same rate - so buying peace
 * always costs in proportion to the dominance that caused the dispute.
 */
public class TributeCalc {

	/** Player's monthly export income from the intel's contested commodities. */
	public static float contestedExportIncome(GrievanceEventIntel intel) {
		float total = 0f;
		for (MarketAPI market : Misc.getPlayerMarkets(false)) {
			for (String commodityId : intel.getCauseCommodityIds()) {
				CommodityOnMarketAPI com = market.getCommodityData(commodityId);
				if (com == null) continue;
				CommodityMarketDataAPI cmd = com.getCommodityMarketData();
				MarketShareDataAPI msd = cmd.getMarketShareData(market);
				if (msd == null) continue;
				total += cmd.getMarketValue() * msd.getMarketValueFraction();
			}
		}
		return total;
	}

	/** Credits per point of resentment bought down. */
	public static float costPerPoint(GrievanceEventIntel intel) {
		float basis = contestedExportIncome(intel) * CommWarsConfig.settleMonths();
		// military grievances: reparations scale with the arsenal they fear
		if (intel.getMilitaryCause() != null) {
			basis = Math.max(basis,
					MilitaryScore.playerScore() * CommWarsConfig.settleMilPerScore());
		}
		float fullCost = Math.max(basis, CommWarsConfig.settleMinimum());
		return fullCost / GrievanceEventIntel.MAX_PROGRESS;
	}

	/** Cost of buying down the given number of resentment points. */
	public static int costFor(GrievanceEventIntel intel, int points) {
		return Math.round(costPerPoint(intel) * points);
	}
}
