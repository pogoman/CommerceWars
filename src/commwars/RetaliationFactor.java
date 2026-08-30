package commwars;

import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseFactorTooltip;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseOneTimeFactor;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipCreator;

/**
 * One-time resentment spike from the player's own hostile act against the
 * aggrieved faction - a raid, a bombardment, an atrocity. Applied through the
 * event framework (rather than a bare setProgress) so the spike shows up in
 * the "Recent one-time factors" panel like any other one-off event, instead
 * of the bar jumping with no visible explanation.
 */
public class RetaliationFactor extends BaseOneTimeFactor {

	protected String desc;

	public RetaliationFactor(int points, String desc) {
		super(points);
		this.desc = desc;
	}

	@Override
	public String getDesc(BaseEventIntel intel) {
		return desc;
	}

	@Override
	public TooltipCreator getMainRowTooltip(BaseEventIntel intel) {
		return new BaseFactorTooltip() {
			@Override
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded,
									  Object tooltipParam) {
				tooltip.addPara("Your own hostile act against this faction's holdings. "
						+ "Resentment surges - and a large enough surge can push the "
						+ "dispute over the edge into enforcement.", 0f);
			}
		};
	}
}
