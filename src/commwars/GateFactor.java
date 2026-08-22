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
 * Shown while the faction (even with every partner it can find) lacks the
 * strength to press its grievance: resentment still simmers, but the bar is
 * held below the ultimatum line. The earned-silence mechanic, made visible.
 */
public class GateFactor extends BaseEventFactor {

	@Override
	public int getProgress(BaseEventIntel intel) {
		return 0;
	}

	@Override
	public boolean shouldShow(BaseEventIntel intel) {
		return intel instanceof GrievanceEventIntel
				&& !((GrievanceEventIntel) intel).isEmboldened();
	}

	@Override
	public String getDesc(BaseEventIntel intel) {
		return "Deterred by your strength";
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
				String factionName = Misc.ucFirst(g.getFaction().getDisplayNameWithArticle());
				tooltip.addPara(factionName + " resents your dominance - but lacks the military "
						+ "strength to press the matter, even counting every ally it could rally. "
						+ "The grievance simmers below the point of ultimatum until the balance "
						+ "of power shifts.", 0f);
				if (CommWarsConfig.debugMode()) {
					float own = MilitaryScore.factionScore(g.getFaction());
					float pooled = CoalitionCalc.pooledScore(g.getFactionId(), g.getCoalitionPartners());
					tooltip.addPara("DEBUG: own %s + partners = pooled %s vs gate threshold %s "
							+ "(player %s x ratio %s)", 10f, h,
							"" + (int) own, "" + (int) pooled,
							"" + (int) CoalitionCalc.gateThreshold(),
							"" + (int) MilitaryScore.playerScore(),
							"" + CommWarsConfig.gateRatio());
				}
			}
		};
	}
}
