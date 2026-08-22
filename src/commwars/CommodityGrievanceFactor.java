package commwars;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseFactorTooltip;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipCreator;
import com.fs.starfarer.api.util.Misc;

/**
 * One row in the grievance event's factor list: monthly resentment from the
 * player out-competing the faction on a single commodity. Reads the live
 * cause snapshot maintained on the intel by {@link GrievanceManager}.
 */
public class CommodityGrievanceFactor extends BaseEventFactor {

	protected String commodityId;

	public CommodityGrievanceFactor(String commodityId) {
		this.commodityId = commodityId;
	}

	public String getCommodityId() {
		return commodityId;
	}

	protected ShareTracker.Cause getCause(BaseEventIntel intel) {
		if (!(intel instanceof GrievanceEventIntel)) return null;
		return ((GrievanceEventIntel) intel).getCause(commodityId);
	}

	protected String getCommodityName() {
		try {
			return Global.getSettings().getCommoditySpec(commodityId).getName();
		} catch (Throwable t) {
			return commodityId;
		}
	}

	@Override
	public int getProgress(BaseEventIntel intel) {
		ShareTracker.Cause cause = getCause(intel);
		if (cause == null) return 0;
		if (intel instanceof GrievanceEventIntel
				&& (((GrievanceEventIntel) intel).isAccrualSuppressed()
						|| ((GrievanceEventIntel) intel).isGateCapped())) {
			return 0;
		}
		return Math.round(cause.weight * CommWarsConfig.clockMult());
	}

	@Override
	public String getProgressStr(BaseEventIntel intel) {
		if (getCause(intel) != null && intel instanceof GrievanceEventIntel
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
		return "Export competition: " + getCommodityName();
	}

	@Override
	public TooltipCreator getMainRowTooltip(final BaseEventIntel intel) {
		return new BaseFactorTooltip() {
			@Override
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				Color h = Misc.getHighlightColor();
				ShareTracker.Cause cause = getCause(intel);
				String factionName = "This faction";
				boolean plural = false;
				if (intel instanceof GrievanceEventIntel) {
					com.fs.starfarer.api.campaign.FactionAPI faction = ((GrievanceEventIntel) intel).getFaction();
					factionName = Misc.ucFirst(faction.getDisplayNameWithArticle());
					plural = "are".equals(faction.getDisplayNameIsOrAre());
				}
				if (cause == null) {
					tooltip.addPara("Your polity's exports of " + getCommodityName().toLowerCase()
							+ " no longer significantly threaten this faction's market position.", 0f);
					return;
				}
				tooltip.addPara(factionName + " " + (plural ? "see" : "sees") + " your polity's exports of "
						+ getCommodityName().toLowerCase() + " as a direct assault on one of "
						+ (plural ? "their" : "its") + " core markets. "
						+ "Your sector export share is %s, against their %s.",
						0f, h, cause.playerShare + "%", cause.factionShare + "%");
				if (CommWarsConfig.debugMode()) {
					tooltip.addPara("DEBUG: weight = weightMult %s x (playerShare %s / factionShare %s) "
							+ "= %s (cap %s), clockMult %s, monthly %s",
							10f, h,
							"" + CommWarsConfig.weightMult(), "" + cause.playerShare, "" + cause.factionShare,
							"" + Misc.getRoundedValueMaxOneAfterDecimal(cause.weight),
							"" + CommWarsConfig.maxMonthlyPerCommodity(),
							"" + CommWarsConfig.clockMult(),
							"" + getProgress(intel));
				}
			}
		};
	}
}
