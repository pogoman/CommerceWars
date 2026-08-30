package commwars;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.util.Misc;

/**
 * Military-industrial capability score: what a faction's actual holdings can
 * support fielding. Grounded in real infrastructure - markets, patrol/military
 * command structures, battlestations, war industry - so a faction cannot
 * "magic up" fleets its economy could never sustain, and disrupting (or
 * destroying) that infrastructure genuinely reduces what it can send.
 *
 * Units are enforcement-difficulty points (10 = roughly one full-size fleet).
 * Also the foundation for the later strength-gate / coalition work: the same
 * score, computed for the player, decides who dares to make demands.
 */
public class MilitaryScore {

	public static float factionScore(FactionAPI faction) {
		float total = 0f;
		for (MarketAPI market : Misc.getFactionMarkets(faction, null)) {
			total += marketScore(market);
		}
		return total;
	}

	public static float playerScore() {
		float total = 0f;
		for (MarketAPI market : Misc.getPlayerMarkets(false)) {
			total += marketScore(market);
		}
		return total;
	}

	public static float marketScore(MarketAPI market) {
		// military infrastructure ONLY - no flat size term. Counting market
		// size let factions with many scattered civilian markets (the
		// independents, above all) sum a paper army that outranked genuinely
		// militarized powers; a trade port with no garrison command projects
		// no force. Size still matters indirectly: bigger markets support
		// bigger military industries.
		float score = 0f;
		for (Industry ind : market.getIndustries()) {
			// disrupted or unbuilt infrastructure projects no force
			if (!ind.isFunctional()) continue;
			score += industryWeight(ind.getId());
		}
		return score;
	}

	/** Weight of one industry in difficulty points. */
	public static float industryWeight(String id) {
		if (Industries.PATROLHQ.equals(id)) return 3f;
		if (Industries.MILITARYBASE.equals(id)) return 8f;
		if (Industries.HIGHCOMMAND.equals(id)) return 14f;

		if (Industries.ORBITALSTATION.equals(id)
				|| Industries.ORBITALSTATION_MID.equals(id)
				|| Industries.ORBITALSTATION_HIGH.equals(id)) return 4f;
		if (Industries.BATTLESTATION.equals(id)
				|| Industries.BATTLESTATION_MID.equals(id)
				|| Industries.BATTLESTATION_HIGH.equals(id)) return 8f;
		if (Industries.STARFORTRESS.equals(id)
				|| Industries.STARFORTRESS_MID.equals(id)
				|| Industries.STARFORTRESS_HIGH.equals(id)) return 14f;

		// war production
		if (Industries.HEAVYINDUSTRY.equals(id)) return 5f;
		if (Industries.ORBITALWORKS.equals(id)) return 8f;

		return 0f;
	}

	/**
	 * A faction's military grievance against the player: their unease at the
	 * player's arsenal rivaling their own. Null when the player is too weak
	 * to notice, the faction is irrelevant, or the player serves under this
	 * faction's flag (a commissioned arsenal is nominally *their* arsenal).
	 */
	public static class MilCause {
		public float playerScore;
		public float factionScore;
		public float weight; // monthly resentment contribution
	}

	public static MilCause computeCause(FactionAPI faction) {
		if (faction.getId().equals(Misc.getCommissionFactionId())) return null;

		float ps = playerScore();
		if (ps < CommWarsConfig.milMinPlayerScore()) return null;

		float fs = factionScore(faction);
		if (fs <= 0) return null;
		if (ps < fs * CommWarsConfig.milNoticeFraction()) return null;

		MilCause cause = new MilCause();
		cause.playerScore = ps;
		cause.factionScore = fs;
		cause.weight = Math.min(CommWarsConfig.maxMonthlyMilitary(),
				CommWarsConfig.milWeightMult() * ps / fs);
		return cause;
	}

	/** Debug breakdown of a faction's score. */
	public static String describe(FactionAPI faction) {
		StringBuilder sb = new StringBuilder();
		int markets = Misc.getFactionMarkets(faction, null).size();
		sb.append(faction.getId()).append(": ").append((int) factionScore(faction))
				.append(" across ").append(markets).append(" market(s)");
		return sb.toString();
	}
}
