package commwars;

import java.util.List;

/**
 * A coalition enforcement action: one fleet group whose fleets are drawn
 * round-robin from every member faction's rosters - League cruisers flying
 * alongside Hegemony wolfpacks. The anchor faction leads (intel, flag,
 * hostility); the hulls tell the story. Inherits the ground-assessment intel
 * section from {@link EnforcementRaidFGI}.
 */
public class CoalitionRaidFGI extends EnforcementRaidFGI {

	protected List<String> members;
	protected int fleetCounter = 0;

	public CoalitionRaidFGI(GenericRaidParams params, List<String> members, boolean heist) {
		super(params, heist);
		this.members = members;
	}

	@Override
	protected String getFleetCreationFactionOverride(int size) {
		if (members == null || members.isEmpty()) return null;
		return members.get((fleetCounter++) % members.size());
	}
}
