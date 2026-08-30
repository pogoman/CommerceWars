package commwars;

import java.util.ArrayList;
import java.util.List;

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

	/**
	 * The factions a settlement of this grievance must satisfy. A coalition
	 * ultimatum is a joint demand: buying peace means buying off every faction
	 * pressing it, not just the one whose screen you opened. For a solo
	 * grievance, that is simply the one faction.
	 */
	public static List<GrievanceEventIntel> settlementParties(GrievanceEventIntel intel) {
		List<GrievanceEventIntel> parties = new ArrayList<GrievanceEventIntel>();
		parties.add(intel);
		if (intel.isCoalitionBacked()) {
			for (String partnerId : intel.getCoalitionPartners()) {
				GrievanceEventIntel partner = GrievanceEventIntel.get(partnerId);
				if (partner != null && partner != intel) parties.add(partner);
			}
		}
		return parties;
	}

	/** True if settling this grievance settles a whole coalition, not just one faction. */
	public static boolean isCoalitionSettlement(GrievanceEventIntel intel) {
		return settlementParties(intel).size() > 1;
	}

	/**
	 * The resentment actually bought down from one party by a settlement of
	 * `points` - clamped to that party's own bar (you cannot pay off more than
	 * it holds).
	 */
	public static int appliedPoints(GrievanceEventIntel party, int points) {
		return Math.max(0, Math.min(points, party.getProgress()));
	}

	/**
	 * Total cost to buy down `points` of resentment across every faction in the
	 * coalition. Each is priced on its own contested-export income (or arsenal),
	 * and clamped to its own bar - so the bill reflects buying off the whole
	 * bloc, in proportion to each member's stake.
	 */
	public static int coalitionCostFor(GrievanceEventIntel intel, int points) {
		int total = 0;
		for (GrievanceEventIntel party : settlementParties(intel)) {
			int applied = appliedPoints(party, points);
			if (applied <= 0) continue;
			total += costFor(party, applied);
		}
		return total;
	}

	/**
	 * Apply a paid coalition settlement: reduce every member's bar by `points`
	 * (clamped to each), charging each its own share. The credits were already
	 * deducted by the caller against {@link #coalitionCostFor}.
	 */
	public static void applyCoalitionSettlement(GrievanceEventIntel intel, int points) {
		for (GrievanceEventIntel party : settlementParties(intel)) {
			int applied = appliedPoints(party, points);
			if (applied <= 0) continue;
			party.paySettlement(applied, costFor(party, applied));
		}
	}
}
