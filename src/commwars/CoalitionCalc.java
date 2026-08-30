package commwars;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;

/**
 * The strength gate and coalition math. A faction only presses demands while
 * its military-industrial power is on par with the player's; too weak alone,
 * it pools with other aggrieved factions it is not hostile to. Defeated
 * coalition members can lose their stomach for the fight and drop out of the
 * pool for a while.
 */
public class CoalitionCalc {

	public static final String DEMORALIZED_KEY = "commwars_demoralized";

	@SuppressWarnings("unchecked")
	protected static Map<String, Long> demoralized() {
		Object val = Global.getSector().getPersistentData().get(DEMORALIZED_KEY);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, Long>();
			Global.getSector().getPersistentData().put(DEMORALIZED_KEY, val);
		}
		return (Map<String, Long>) val;
	}

	public static boolean isDemoralized(String factionId) {
		Long t = demoralized().get(factionId);
		if (t == null) return false;
		float days = Global.getSector().getClock().getElapsedDaysSince(t);
		if (days >= CommWarsConfig.coalitionDropoutDays()) {
			demoralized().remove(factionId);
			return false;
		}
		return true;
	}

	public static void demoralize(String factionId) {
		demoralized().put(factionId, Global.getSector().getClock().getTimestamp());
	}

	public static float demoralizedDaysLeft(String factionId) {
		Long t = demoralized().get(factionId);
		if (t == null) return 0f;
		float days = Global.getSector().getClock().getElapsedDaysSince(t);
		return Math.max(0f, CommWarsConfig.coalitionDropoutDays() - days);
	}

	/**
	 * Potential coalition partners for a faction: other actively-aggrieved
	 * factions it is not hostile to, and that still have the stomach for it.
	 */
	public static List<String> partnersFor(String factionId, Collection<String> activeIds) {
		FactionAPI faction = Global.getSector().getFaction(factionId);
		List<String> result = new ArrayList<String>();
		String commissionFaction = com.fs.starfarer.api.util.Misc.getCommissionFactionId();
		for (String other : activeIds) {
			if (other.equals(factionId)) continue;
			if (isDemoralized(other)) continue;
			// a faction you serve won't join a coalition against you
			if (other.equals(commissionFaction)) continue;
			FactionAPI otherFaction = Global.getSector().getFaction(other);
			if (otherFaction == null) continue;
			if (faction.isHostileTo(otherFaction)) continue;
			GrievanceEventIntel otherIntel = GrievanceEventIntel.get(other);
			if (otherIntel == null) continue;
			// a faction with fleets already committed to an enforcement action -
			// its own, or backing another's - is not available for a new coalition
			if (otherIntel.isStrikeActive()
					|| otherIntel.getSupportingStrikeFor() != null) {
				continue;
			}
			// a faction only fights for someone else's grievance if it is
			// meaningfully angry itself - settlements can buy partners out
			if (otherIntel.getProgress() < CommWarsConfig.coalitionMinAnger()) {
				continue;
			}
			result.add(other);
		}
		return result;
	}

	public static float pooledScore(String factionId, List<String> partners) {
		float total = MilitaryScore.factionScore(Global.getSector().getFaction(factionId));
		for (String partner : partners) {
			total += MilitaryScore.factionScore(Global.getSector().getFaction(partner));
		}
		return total;
	}

	/**
	 * One coalition anchor at a time, SECTOR-WIDE: of every too-weak faction
	 * that could front a coalition this tick, only the strongest actually
	 * does - the rest stay gated (and back the anchor's bloc where eligible).
	 * A per-pool leadership contest is not enough: two weak factions whose
	 * mutual anger is below the partner threshold never appear in each
	 * other's pools, so each crowns itself leader of "its own" bloc borrowed
	 * from the same strong backers - N overlapping coalition screens again.
	 * Frozen grievances (fleets committed) keep their state and don't compete.
	 */
	public static String computeBlocAnchor(Collection<GrievanceEventIntel> active) {
		float threshold = gateThreshold();
		String anchor = null;
		float best = -1f;
		for (GrievanceEventIntel intel : active) {
			if (intel.isStrikeActive() || intel.getSupportingStrikeFor() != null) continue;
			FactionAPI faction = intel.getFaction();
			if (faction == null) continue;
			float own = MilitaryScore.factionScore(faction);
			if (own >= threshold) continue; // presses solo, needs no coalition
			if (own > best || (own == best && anchor != null
					&& intel.getFactionId().compareTo(anchor) < 0)) {
				best = own;
				anchor = intel.getFactionId();
			}
		}
		return anchor;
	}

	public static float gateThreshold() {
		return MilitaryScore.playerScore() * CommWarsConfig.gateRatio();
	}

	/** Debug string listing every currently-demoralized faction. */
	public static String describeDemoralized() {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Long> e : new LinkedHashMap<String, Long>(demoralized()).entrySet()) {
			if (!isDemoralized(e.getKey())) continue; // prunes expired entries
			if (sb.length() > 0) sb.append(", ");
			sb.append(e.getKey()).append(" (")
					.append((int) demoralizedDaysLeft(e.getKey())).append("d left)");
		}
		return sb.length() > 0 ? sb.toString() : "none";
	}
}
