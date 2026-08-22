package commwars;

import java.awt.Color;

import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseFactorTooltip;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipCreator;
import com.fs.starfarer.api.util.Misc;

/**
 * Explains why resentment accrual is frozen: the player is paying tribute,
 * or a post-enforcement truce is in effect. Contributes no progress itself -
 * suppression is applied inside {@link CommodityGrievanceFactor}.
 */
public class SuppressionFactor extends BaseEventFactor {

	@Override
	public int getProgress(BaseEventIntel intel) {
		return 0;
	}

	@Override
	public boolean shouldShow(BaseEventIntel intel) {
		return intel instanceof GrievanceEventIntel
				&& ((GrievanceEventIntel) intel).isAccrualSuppressed();
	}

	@Override
	public String getDesc(BaseEventIntel intel) {
		GrievanceEventIntel g = (GrievanceEventIntel) intel;
		if (g.isStrikeActive()) {
			return "Enforcement underway";
		}
		if (g.getSupportingStrikeFor() != null) {
			return "Joint enforcement underway";
		}
		return "Post-enforcement truce";
	}

	@Override
	public String getProgressStr(BaseEventIntel intel) {
		return NEGATED_FACTOR_PROGRESS;
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
				GrievanceEventIntel g = (GrievanceEventIntel) intel;
				Color h = Misc.getHighlightColor();
				if (g.isStrikeActive()) {
					tooltip.addPara("Enforcement fleets are underway. The dispute is being settled "
							+ "by force - resentment is not tallied while the outcome hangs in "
							+ "the balance.", 0f);
				} else if (g.getSupportingStrikeFor() != null) {
					com.fs.starfarer.api.campaign.FactionAPI anchor =
							com.fs.starfarer.api.Global.getSector()
									.getFaction(g.getSupportingStrikeFor());
					tooltip.addPara("This faction's contingents sail with the coalition "
							+ "enforcement action led by "
							+ (anchor != null ? anchor.getDisplayNameWithArticle() : "an ally")
							+ ". The joint strike speaks for every member's grievance - their "
							+ "own ledger was vented into it and holds until the outcome is "
							+ "known.", 0f);
				} else {
					tooltip.addPara("A recent enforcement action made its point. Resentment from export "
							+ "competition is frozen for another %s days.",
							0f, h, "" + (int) g.getTruceDaysLeft());
				}
			}
		};
	}
}
