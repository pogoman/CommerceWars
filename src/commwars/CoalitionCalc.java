package commwars;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;

/**
 * The strength gate and coalition math. The rules are deliberately simple:
 *   1. A faction that can get the job done on its own - field enough force to
 *      crack the target it would actually strike, the player's own fleet
 *      included - presses solo. No coalition.
 *   2. A faction that cannot pools ONLY with allies who also cannot; a
 *      solo-capable faction never rides in (or props up) someone else's bloc.
 *   3. Too weak even pooled: the grievance simmers below the ultimatum line.
 * "Enough force" and "what they can field" are measured in the same units the
 * strike sizing uses, so a faction that passes the gate can always actually
 * send a credible strike. Defeated coalition members can lose their stomach
 * for the fight and drop out of the pool for a while.
 */
public class CoalitionCalc {

	public static final String DEMORALIZED_KEY = "commwars_demoralized";

	/**
	 * Hysteresis on gate classifications: in-system defense strength wobbles
	 * as patrols spawn and die (and as the player's fleet changes), so a
	 * faction that qualified - solo-capable, or a credible bloc - keeps the
	 * status until it falls clearly short of the requirement, not the instant
	 * a patrol respawns. Without this, factions flap between solo grievances
	 * and coalitions tick to tick.
	 */
	public static final float STICKY_MARGIN = 0.8f;

	/**
	 * What this faction can genuinely commit to one enforcement action, in
	 * strike-difficulty points - its military-industrial score, nothing held
	 * back. The same number caps the strike budget at launch.
	 */
	public static float fieldable(FactionAPI faction) {
		return MilitaryScore.factionScore(faction) * CommWarsConfig.fieldableFraction();
	}

	/**
	 * Rule 1: can this faction hold its own - field enough force to crack the
	 * target its enforcement strike would actually go after, the player's own
	 * fleet included? Sticky (see STICKY_MARGIN). True when the player has no
	 * strikeable colonies: there is no job to size against, hence no coalition.
	 */
	public static boolean canPressSolo(GrievanceEventIntel intel) {
		float needed = EnforcementStrike.neededFor(intel);
		if (needed <= 0f) return true;
		boolean held = intel.isEmboldened() && intel.getCoalitionPartners().isEmpty();
		return fieldable(intel.getFaction()) >= needed * (held ? STICKY_MARGIN : 1f);
	}

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
			// a blood feud is pursued alone - a vendetta faction neither
			// fronts nor backs a trade coalition
			if (otherIntel.isVendetta()) continue;
			// Rule 2: coalitions are unions of the weak. A faction that can
			// press its demands on its own fights its own corner - it never
			// rides in anyone else's bloc, so its strength is never
			// double-counted, and no bloc collapses the moment a strong
			// member turns to its own grievance.
			if (canPressSolo(otherIntel)) continue;
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

	public static float pooledFieldable(String factionId, List<String> partners) {
		float total = fieldable(Global.getSector().getFaction(factionId));
		for (String partner : partners) {
			total += fieldable(Global.getSector().getFaction(partner));
		}
		return total;
	}

	/**
	 * One coalition anchor at a time, SECTOR-WIDE: of every too-weak faction
	 * that could front a coalition this tick, only one actually does - the
	 * rest stay gated (and back the anchor's bloc where eligible). A per-pool
	 * leadership contest is not enough: two weak factions whose mutual anger
	 * is below the partner threshold never appear in each other's pools, so
	 * each crowns itself leader of "its own" bloc borrowed from the same
	 * backers - N overlapping coalition screens again. Frozen grievances
	 * (fleets committed) keep their state and don't compete. The incumbent
	 * anchor keeps the role while it remains eligible: re-crowning whenever
	 * raw strengths cross would dissolve one bloc and announce another for
	 * no real reason.
	 */
	public static String computeBlocAnchor(Collection<GrievanceEventIntel> active,
										   String prevAnchor) {
		String anchor = null;
		float best = -1f;
		for (GrievanceEventIntel intel : active) {
			if (intel.isStrikeActive() || intel.getSupportingStrikeFor() != null) continue;
			// a patron does not front a coalition against its own commissioned
			// client: exclude it from the anchor contest entirely, so the bloc
			// forms around a legitimate leader (or not at all) instead of the
			// faction you serve.
			if (intel.isCommissionSuppressed()) continue;
			// a blood feud never fronts a coalition: its strike would not
			// split, so a bloc's pooled strength would be fleets that never
			// actually sail
			if (intel.isVendetta()) continue;
			FactionAPI faction = intel.getFaction();
			if (faction == null) continue;
			if (canPressSolo(intel)) continue; // presses solo, needs no coalition
			if (intel.getFactionId().equals(prevAnchor)) return prevAnchor;
			float own = fieldable(faction);
			if (own > best || (own == best && anchor != null
					&& intel.getFactionId().compareTo(anchor) < 0)) {
				best = own;
				anchor = intel.getFactionId();
			}
		}
		return anchor;
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
