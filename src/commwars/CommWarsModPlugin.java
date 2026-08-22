package commwars;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

public class CommWarsModPlugin extends BaseModPlugin {

	@Override
	public void onGameLoad(boolean newGame) {
		// transient: re-added every load, never serialized into the save
		Global.getSector().addTransientScript(new GrievanceManager());
		Global.getSector().getListenerManager().addListener(new StolenItemListener(), true);
	}
}
