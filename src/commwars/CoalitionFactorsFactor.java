package commwars;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.EventFactor;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipCreator;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipLocation;
import com.fs.starfarer.api.util.Misc;

/**
 * A display-only factor on a coalition anchor's grievance. It contributes no
 * progress of its own; it simply appends each coalition partner's own monthly
 * factors to the anchor's "Monthly factors" panel, grouped under a per-faction
 * heading. The numbers shown are the partners' real factors, evaluated against
 * each partner's own grievance, so the player can see the whole coalition's
 * monthly resentment in one place - matching the consolidated screen where the
 * partners' separate (paused) entries are hidden.
 *
 * Kept last in the anchor's factor list (see {@link GrievanceEventIntel#updateCauses})
 * so the partner blocks render beneath the anchor's own factors.
 */
public class CoalitionFactorsFactor extends BaseEventFactor {

	@Override
	public boolean shouldShow(BaseEventIntel intel) {
		if (!(intel instanceof GrievanceEventIntel)) return false;
		GrievanceEventIntel g = (GrievanceEventIntel) intel;
		return g.isCoalitionBacked() && !g.getCoalitionPartners().isEmpty();
	}

	/**
	 * The raw monthly resentment a factor would contribute if the partner's
	 * bar were not pinned at the gate cap - the honest "why they back the
	 * bloc" number. Null for factor types without a live cause snapshot.
	 */
	protected static Integer rawMonthly(EventFactor pf, GrievanceEventIntel partner) {
		if (pf instanceof CommodityGrievanceFactor) {
			ShareTracker.Cause cause = partner.getCause(
					((CommodityGrievanceFactor) pf).getCommodityId());
			if (cause == null) return null;
			return Math.round(cause.weight * CommWarsConfig.clockMult());
		}
		if (pf instanceof MilitaryGrievanceFactor) {
			MilitaryScore.MilCause cause = partner.getMilitaryCause();
			if (cause == null) return null;
			return Math.round(cause.weight * CommWarsConfig.clockMult());
		}
		return null;
	}

	@Override
	public void addExtraRows(TooltipMakerAPI info, BaseEventIntel intel) {
		if (!(intel instanceof GrievanceEventIntel)) return;
		GrievanceEventIntel anchor = (GrievanceEventIntel) intel;
		if (!anchor.isCoalitionBacked()) return;

		for (String partnerId : anchor.getCoalitionPartners()) {
			GrievanceEventIntel partner = GrievanceEventIntel.get(partnerId);
			if (partner == null) continue;
			FactionAPI faction = partner.getFaction();
			if (faction == null) continue;

			// collect the partner's visible monthly factors first, so a partner
			// with nothing to show doesn't leave a dangling heading. The gate
			// row is dropped here: on its own (hidden) screen "Deterred by
			// your strength" explains the held bar, but on the ANCHOR's screen
			// this partner is plainly part of the coalition pressing you -
			// showing it as "deterred" is a contradiction. Its role is stated
			// by a "Backing the coalition" row instead.
			boolean gated = false;
			List<EventFactor> rows = new ArrayList<EventFactor>();
			for (EventFactor pf : partner.getFactors()) {
				if (pf.isOneTime()) continue;
				if (pf instanceof CoalitionFactorsFactor) continue; // never nest
				if (pf instanceof GateFactor) {
					if (pf.shouldShow(partner)) gated = true;
					continue;
				}
				if (!pf.shouldShow(partner)) continue;
				if (pf.getDesc(partner) == null) continue;
				rows.add(pf);
			}
			if (rows.isEmpty() && !gated) continue;

			// per-faction heading row, the name in that faction's colour
			info.addRowWithGlow(Alignment.LMID, faction.getBaseUIColor(),
					faction.getDisplayName() + ":", Alignment.RMID, Misc.getGrayColor(), "");

			if (gated) {
				info.addRowWithGlow(Alignment.LMID, Misc.getGrayColor(),
						"    Backing the coalition",
						Alignment.RMID, Misc.getGrayColor(), "");
			}
			for (EventFactor pf : rows) {
				String value = pf.getProgressStr(partner);
				java.awt.Color valueColor = pf.getProgressColor(partner);
				// a gated backer's own bar pins at the gate cap, dashing out
				// its factor values - but on the ANCHOR's screen these rows
				// exist to show WHY the partner backs the bloc, so show the
				// raw monthly cause weight instead of a meaningless "---".
				// Real suppression (truce, joint enforcement) keeps its dashes.
				if (partner.isGateCapped() && !partner.isAccrualSuppressed()) {
					Integer raw = rawMonthly(pf, partner);
					if (raw != null && raw != 0) {
						value = (raw > 0 ? "+" : "") + raw;
						valueColor = partner.getProgressColor(raw);
					}
				}
				info.addRowWithGlow(Alignment.LMID, pf.getDescColor(partner),
						"    " + pf.getDesc(partner),
						Alignment.RMID, valueColor, value);
				TooltipCreator tc = pf.getMainRowTooltip(partner);
				if (tc != null) {
					info.addTooltipToAddedRow(tc, TooltipLocation.RIGHT, false);
				}
			}
		}
	}
}
