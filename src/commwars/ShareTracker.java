package commwars;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.CommodityMarketDataAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * Computes, per faction, which commodities the player is out-competing them
 * on and how much monthly resentment that generates. The share-comparison
 * idiom follows vanilla's (retired) PunitiveExpeditionManager: a faction
 * holds a grievance over a commodity when it is a significant producer, the
 * player's export share is large enough to notice, and the player's share
 * rivals the faction's own.
 */
public class ShareTracker {

	public static class Cause {
		public String commodityId;
		public int playerShare;   // sector export market share, percent
		public int factionShare;  // sector export market share, percent
		public float weight;      // monthly resentment contribution

		public Cause(String commodityId, int playerShare, int factionShare, float weight) {
			this.commodityId = commodityId;
			this.playerShare = playerShare;
			this.factionShare = factionShare;
			this.weight = weight;
		}
	}

	/** Faction id -> active causes. Factions with no causes are absent. */
	public static Map<String, List<Cause>> compute() {
		Map<String, List<Cause>> result = new LinkedHashMap<String, List<Cause>>();
		FactionAPI player = Global.getSector().getPlayerFaction();

		for (FactionAPI faction : Global.getSector().getAllFactions()) {
			if (faction.isPlayerFaction()) continue;
			if (Factions.NEUTRAL.equals(faction.getId())) continue;

			List<MarketAPI> markets = Misc.getFactionMarkets(faction, null);
			if (markets.isEmpty()) continue;

			List<Cause> causes = computeFor(faction, markets.get(0), player);
			if (!causes.isEmpty()) {
				result.put(faction.getId(), causes);
			}
		}
		return result;
	}

	private static List<Cause> computeFor(FactionAPI faction, MarketAPI test, FactionAPI player) {
		List<Cause> result = new ArrayList<Cause>();

		for (CommodityOnMarketAPI com : test.getAllCommodities()) {
			if (com.isNonEcon()) continue;
			// factions don't officially complain about goods they outlaw
			if (faction.isIllegal(com.getId())) continue;

			CommodityMarketDataAPI cmd = com.getCommodityMarketData();
			if (cmd.getMarketValue() <= 0) continue;

			Map<FactionAPI, Integer> shares = cmd.getMarketSharePercentPerFaction();
			Integer factionShare = shares.get(faction);
			if (factionShare == null || factionShare < CommWarsConfig.minFactionShare()) continue;

			// the faction must be one of the top producers, not counting the
			// player - "they used to matter in this market" holds even after
			// the player takes it over
			int numHigher = 0;
			for (Map.Entry<FactionAPI, Integer> e : shares.entrySet()) {
				if (e.getKey() == faction || e.getKey() == player) continue;
				if (e.getValue() > factionShare) numHigher++;
			}
			if (numHigher >= CommWarsConfig.topProducers()) continue;

			int playerShare = cmd.getMarketSharePercent(player);
			if (playerShare < CommWarsConfig.minPlayerShare()) continue;
			if (playerShare < factionShare * CommWarsConfig.noticeFraction()) continue;

			float weight = CommWarsConfig.weightMult() * (float) playerShare / (float) factionShare;
			weight = Math.min(weight, CommWarsConfig.maxMonthlyPerCommodity());

			result.add(new Cause(com.getId(), playerShare, factionShare, weight));
		}
		return result;
	}

	public static float totalWeight(List<Cause> causes) {
		float total = 0f;
		for (Cause c : causes) total += c.weight;
		return total;
	}
}
