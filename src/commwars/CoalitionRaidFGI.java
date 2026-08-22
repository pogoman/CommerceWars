package commwars;

import java.util.List;

import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;

/**
 * A coalition enforcement action: one fleet group whose fleets are drawn
 * round-robin from every member faction's rosters - League cruisers flying
 * alongside Hegemony wolfpacks. The anchor faction leads (intel, flag,
 * hostility); the hulls tell the story.
 */
public class CoalitionRaidFGI extends GenericRaidFGI {

	protected List<String> members;
	protected int fleetCounter = 0;

	public CoalitionRaidFGI(GenericRaidParams params, List<String> members) {
		super(params);
		this.members = members;
	}

	@Override
	protected String getFleetCreationFactionOverride(int size) {
		if (members == null || members.isEmpty()) return null;
		return members.get((fleetCounter++) % members.size());
	}
}
