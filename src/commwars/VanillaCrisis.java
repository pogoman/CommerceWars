package commwars;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.events.BaseEventIntel.EventStageData;
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


	/** True if the faction has a vanilla colony crisis active or building. */
	public static boolean isActiveOrPending(String factionId) {
		if (!CommWarsConfig.deferToVanillaCrises()) return false;

		Class<?> factorClass = FACTOR_BY_FACTION.get(factionId);
		if (factorClass == null) return false;

		HostileActivityEventIntel ha = HostileActivityEventIntel.get();
		if (ha == null) return false;

		// Defer only when an actual crisis EVENT has been rolled for this
		// faction (an imminent attack) - one coercion campaign at a time.
		// Every faction's baseline hostile-activity contribution is a
		// perpetual background condition (colony presence, AI-core use, fuel
		// production, cells, bases) that never stops while the player holds
		// the colony/tech/fuel; deferring on that would gag the grievance
		// meter forever, whatever the vanilla factor is even about.
		EventStageData esd = ha.getDataFor(HostileActivityEventIntel.Stage.HA_EVENT);
		if (esd != null && esd.rollData instanceof HAERandomEventData
				&& factorClass.isInstance(((HAERandomEventData) esd.rollData).factor)) {
			return true;
		}
		return false;
	}
}
