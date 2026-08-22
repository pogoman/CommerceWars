package commwars;

import java.awt.Color;

import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseFactorTooltip;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipCreator;
import com.fs.starfarer.api.util.Misc;

/**
 * Negative factor that drains the grievance bar while the faction has no
 * active causes against the player - i.e. the player complied with the
 * demand, or their market dominance lapsed on its own.
 */
public class GrievanceDecayFactor extends BaseEventFactor {

	@Override
	public int getProgress(BaseEventIntel intel) {
		if (intel instanceof GrievanceEventIntel
				&& ((GrievanceEventIntel) intel).isVendetta()) {
			return 0; // blood feuds do not cool
		}
		if (intel instanceof GrievanceEventIntel && !((GrievanceEventIntel) intel).hasCauses()) {
			return -Math.round(CommWarsConfig.decayPerMonth() * CommWarsConfig.clockMult());
		}
		return 0;
	}

	@Override
	public boolean shouldShow(BaseEventIntel intel) {
		return getProgress(intel) != 0;
	}

	@Override
	public String getDesc(BaseEventIntel intel) {
		return "No active trade disputes";
	}

	@Override
	public Color getDescColor(BaseEventIntel intel) {
		return Misc.getPositiveHighlightColor();
	}

	@Override
	public Color getProgressColor(BaseEventIntel intel) {
		return Misc.getPositiveHighlightColor();
	}

	@Override
	public TooltipCreator getMainRowTooltip(final BaseEventIntel intel) {
		return new BaseFactorTooltip() {
			@Override
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				tooltip.addPara("Your polity's exports no longer significantly threaten this faction's "
						+ "market position, and its resentment is cooling. If nothing rekindles the "
						+ "dispute, the grievance will eventually be dropped.", 0f);
			}
		};
	}
}
