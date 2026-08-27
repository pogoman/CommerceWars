package commwars;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;

/**
 * An enforcement raid whose intel entry, in addition to vanilla's space-only
 * assessment ("N fleets, defenders outmatched, likely success"), shows a
 * GROUND assessment: whether the raiders can actually break the target's
 * ground defenses (planetary shield and garrison marines included). The
 * vanilla "success" only means the fleets reach the planet - it says nothing
 * about whether a shielded, fortified colony will repel the landing. This is
 * re-rendered every view, so it reflects the player's current defenses.
 */
public class EnforcementRaidFGI extends GenericRaidFGI {

	protected boolean heist;

	public EnforcementRaidFGI(GenericRaidParams params, boolean heist) {
		super(params);
		this.heist = heist;
	}

	protected MarketAPI getGroundTarget() {
		if (params.raidParams.allowedTargets.isEmpty()) return null;
		return params.raidParams.allowedTargets.get(0);
	}

	@Override
	protected void addPostAssessmentSection(TooltipMakerAPI info, float width, float height, float opad) {
		super.addPostAssessmentSection(info, width, height, opad);
		if (params.raidParams.bombardment != null) return; // bombardment, not a ground raid
		EnforcementStrike.appendGroundAssessment(info, getFleets(), getGroundTarget(), heist, opad);
	}
}
