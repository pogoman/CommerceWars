package commwars;

import java.awt.Color;

import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseFactorTooltip;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipCreator;
import com.fs.starfarer.api.util.Misc;

/**
 * The military-track factor row: monthly resentment from the player's
 * military-industrial buildup rivaling the faction's own. Reads the live
 * snapshot maintained on the intel by {@link GrievanceManager}.
 */
public class MilitaryGrievanceFactor extends BaseEventFactor {

	protected MilitaryScore.MilCause getCause(BaseEventIntel intel) {
		if (!(intel instanceof GrievanceEventIntel)) return null;
		return ((GrievanceEventIntel) intel).getMilitaryCause();
	}

	@Override
	public int getProgress(BaseEventIntel intel) {
		MilitaryScore.MilCause cause = getCause(intel);
		if (cause == null) return 0;
		GrievanceEventIntel g = (GrievanceEventIntel) intel;
		if (g.isAccrualSuppressed() || g.isGateCapped()) return 0;
		return Math.round(cause.weight * CommWarsConfig.clockMult());
	}

	@Override
	public String getProgressStr(BaseEventIntel intel) {
		if (getCause(intel) != null
				&& (((GrievanceEventIntel) intel).isAccrualSuppressed()
						|| ((GrievanceEventIntel) intel).isGateCapped())) {
			return NEGATED_FACTOR_PROGRESS;
		}
		return super.getProgressStr(intel);
	}

	@Override
	public boolean shouldShow(BaseEventIntel intel) {
		return getCause(intel) != null;
	}

	@Override
	public String getDesc(BaseEventIntel intel) {
		return "Military buildup";
	}

	@Override
	public TooltipCreator getMainRowTooltip(final BaseEventIntel intel) {
		return new BaseFactorTooltip() {
			@Override
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				Color h = Misc.getHighlightColor();
				MilitaryScore.MilCause cause = getCause(intel);
				GrievanceEventIntel g = (GrievanceEventIntel) intel;
				boolean plural = "are".equals(g.getFaction().getDisplayNameIsOrAre());
				String factionName = Misc.ucFirst(g.getFaction().getDisplayNameWithArticle());
				if (cause == null) {
					tooltip.addPara("Your polity's military no longer rivals this faction's own.", 0f);
					return;
				}
				tooltip.addPara(factionName + " " + (plural ? "watch" : "watches")
						+ " your polity's growing arsenal - high commands, battlestations, war "
						+ "industry - with mounting alarm. Your military-industrial score is %s "
						+ "against their %s.",
						0f, h, "" + (int) cause.playerScore, "" + (int) cause.factionScore);
				tooltip.addPara("A commission with them would put your arsenal nominally under "
						+ "their flag, laying this concern to rest for as long as you serve.",
						10f);
				if (CommWarsConfig.debugMode()) {
					tooltip.addPara("DEBUG: weight = milWeightMult %s x (playerScore %s / "
							+ "factionScore %s) = %s (cap %s), monthly %s", 10f, h,
							"" + CommWarsConfig.milWeightMult(),
							"" + (int) cause.playerScore, "" + (int) cause.factionScore,
							Misc.getRoundedValueMaxOneAfterDecimal(cause.weight),
							"" + CommWarsConfig.maxMonthlyMilitary(),
							"" + getProgress(intel));
				}
			}
		};
	}
}
