package commwars;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * Legacy single-fleet-group coalition raid (round-robin rosters under the
 * anchor's flag). Superseded by launching one raid per coalition member
 * (each under its own flag; see {@link EnforcementStrike#launch}), which reads
 * as a joint operation far more clearly. Retained only so any such raid still
 * in flight in an existing save continues to deserialize and resolve; it is no
 * longer created for new strikes. Inherits the ground-assessment intel section
 * from {@link EnforcementRaidFGI}.
 */
public class CoalitionRaidFGI extends EnforcementRaidFGI {

	protected List<String> members;
	protected int fleetCounter = 0;

	public CoalitionRaidFGI(GenericRaidParams params, List<String> members, boolean heist,
			float projectedRaidStr) {
		super(params, heist, projectedRaidStr);
		this.members = members;
	}

	@Override
	protected String getFleetCreationFactionOverride(int size) {
		if (members == null || members.isEmpty()) return null;
		return members.get((fleetCounter++) % members.size());
	}

	/**
	 * Name the coalition partners flying with the anchor, each in its own
	 * faction colour - vanilla's description names only the leading faction,
	 * so a joint action would otherwise read as one faction acting alone.
	 */
	@Override
	protected void addBasicDescription(TooltipMakerAPI info, float width, float height, float opad) {
		super.addBasicDescription(info, width, height, opad);
		if (members == null || members.size() <= 1) return;

		List<String> names = new ArrayList<String>();
		List<Color> colors = new ArrayList<Color>();
		// members.get(0) is the anchor, already named in the base description
		for (int i = 1; i < members.size(); i++) {
			FactionAPI f = Global.getSector().getFaction(members.get(i));
			if (f == null) continue;
			names.add(f.getDisplayName());
			colors.add(f.getBaseUIColor());
		}
		if (names.isEmpty()) return;

		info.addPara("Coalition contingents from " + Misc.getAndJoined(names.toArray(new String[0]))
				+ " sail alongside them, their ships mingled in the same fleets.", opad,
				colors.toArray(new Color[0]), names.toArray(new String[0]));
	}
}
