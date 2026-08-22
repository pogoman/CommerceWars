package commwars;

import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel;

/**
 * Inert stub kept only so saves from the recurring-tribute builds still
 * deserialize. Instances are removed by GrievanceEventIntel.ensureFactors();
 * tribute is now one-off settlement payments handled in the orders dialog.
 */
public class TributeFactor extends BaseEventFactor {

	@Override
	public int getProgress(BaseEventIntel intel) {
		return 0;
	}

	@Override
	public boolean shouldShow(BaseEventIntel intel) {
		return false;
	}
}
