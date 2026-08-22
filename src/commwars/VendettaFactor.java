package commwars;

import java.awt.Color;

import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseFactorTooltip;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipCreator;
import com.fs.starfarer.api.util.Misc;

/**
 * The blood debt: a fixed monthly drive toward reprisal that replaces all
 * commodity and military causes once a grievance converts to a vendetta.
 * Unaffected by market shares, compliance, settlements, or anything else the
 * player can do short of ending the feud the hard way.
 */
public class VendettaFactor extends BaseEventFactor {

	protected GrievanceEventIntel intel(BaseEventIntel intel) {
		return intel instanceof GrievanceEventIntel ? (GrievanceEventIntel) intel : null;
	}

	@Override
	public int getProgress(BaseEventIntel intel) {
		GrievanceEventIntel g = intel(intel);
		if (g == null || !g.isVendetta()) return 0;
		if (g.isAccrualSuppressed() || g.isGateCapped()) return 0;
		return Math.round(CommWarsConfig.vendettaPerMonth() * CommWarsConfig.clockMult());
	}

	@Override
	public String getProgressStr(BaseEventIntel intel) {
		GrievanceEventIntel g = intel(intel);
		if (g != null && g.isVendetta()
				&& (g.isAccrualSuppressed() || g.isGateCapped())) {
			return NEGATED_FACTOR_PROGRESS;
		}
		return super.getProgressStr(intel);
	}

	@Override
	public boolean shouldShow(BaseEventIntel intel) {
		GrievanceEventIntel g = intel(intel);
		return g != null && g.isVendetta();
	}

	@Override
	public String getDesc(BaseEventIntel intel) {
		return "The blood debt";
	}

	@Override
	public Color getDescColor(BaseEventIntel intel) {
		return Misc.getNegativeHighlightColor();
	}

	@Override
	public TooltipCreator getMainRowTooltip(final BaseEventIntel intel) {
		return new BaseFactorTooltip() {
			@Override
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				GrievanceEventIntel g = intel(intel);
				String factionName = g != null
						? Misc.ucFirst(g.getFaction().getDisplayNameWithArticle())
						: "This faction";
				tooltip.addPara(factionName + " does not want your trade concessions or your "
						+ "money. You saturation-bombed their world; the drive toward reprisal "
						+ "is constant, and nothing vents it except a strike - theirs, "
						+ "or yours.", 0f);
			}
		};
	}
}
