package commwars;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel.EventStageData;
import com.fs.starfarer.api.impl.campaign.intel.events.EventFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.HegemonyHostileActivityFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.HostileActivityEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.HostileActivityEventIntel.HAERandomEventData;
import com.fs.starfarer.api.impl.campaign.intel.events.LuddicChurchHostileActivityFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.LuddicPathHostileActivityFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.PerseanLeagueHostileActivityFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.PirateHostileActivityFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.SindrianDiktatHostileActivityFactor;
import com.fs.starfarer.api.impl.campaign.intel.events.TriTachyonHostileActivityFactor;

/**
 * Bridge to the vanilla colony-crisis system: a faction that is already
 * pressuring the player through a vanilla crisis (active rolled event, or a
 * factor contributing to the hostile activity bar) gets no Commerce Wars
 * grievance on top - most would be the same complaint twice (Sindria and
 * fuel, the League and size...).
 */
public class VanillaCrisis {

	private static final Map<String, Class<?>> FACTOR_BY_FACTION = new HashMap<String, Class<?>>();
	static {
		FACTOR_BY_FACTION.put(Factions.HEGEMONY, HegemonyHostileActivityFactor.class);
		FACTOR_BY_FACTION.put(Factions.PERSEAN, PerseanLeagueHostileActivityFactor.class);
		FACTOR_BY_FACTION.put(Factions.DIKTAT, SindrianDiktatHostileActivityFactor.class);
		FACTOR_BY_FACTION.put(Factions.TRITACHYON, TriTachyonHostileActivityFactor.class);
		FACTOR_BY_FACTION.put(Factions.LUDDIC_CHURCH, LuddicChurchHostileActivityFactor.class);
		FACTOR_BY_FACTION.put(Factions.LUDDIC_PATH, LuddicPathHostileActivityFactor.class);
		FACTOR_BY_FACTION.put(Factions.PIRATES, PirateHostileActivityFactor.class);
	}

	/**
	 * Factions whose hostile-activity contribution is a recurring background
	 * condition (cells, bases) rather than a one-shot resolvable campaign:
	 * for these, only an actually-rolled crisis event defers the grievance -
	 * perpetual background pressure must not gag the meter forever.
	 */
	private static final java.util.Set<String> RECURRING_PRESSURE =
			new java.util.HashSet<String>(java.util.Arrays.asList(
					Factions.LUDDIC_PATH, Factions.PIRATES));

	/** True if the faction has a vanilla colony crisis active or building. */
	public static boolean isActiveOrPending(String factionId) {
		if (!CommWarsConfig.deferToVanillaCrises()) return false;

		Class<?> factorClass = FACTOR_BY_FACTION.get(factionId);
		if (factorClass == null) return false;

		HostileActivityEventIntel ha = HostileActivityEventIntel.get();
		if (ha == null) return false;

		// an actual crisis event has been rolled for this faction
		EventStageData esd = ha.getDataFor(HostileActivityEventIntel.Stage.HA_EVENT);
		if (esd != null && esd.rollData instanceof HAERandomEventData
				&& factorClass.isInstance(((HAERandomEventData) esd.rollData).factor)) {
			return true;
		}

		// recurring-pressure factions (Path cells, pirate bases) never stop
		// contributing - mere pending pressure doesn't defer their grievances
		if (RECURRING_PRESSURE.contains(factionId)) {
			return false;
		}

		// their factor is actively building the crisis bar
		for (EventFactor factor : ha.getFactors()) {
			if (factorClass.isInstance(factor) && factor.getProgress(ha) > 0) {
				return true;
			}
		}
		return false;
	}
}
