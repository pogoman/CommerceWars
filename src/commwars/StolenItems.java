package commwars;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;

/**
 * Registry of installed items stolen by enforcement heists. Everything
 * physically goes somewhere: each record names the market holding the item
 * (and the industry it was re-installed into, if any), so the player can
 * raid it back - see {@link StolenItemListener}.
 */
public class StolenItems {

	public static final String KEY = "commwars_stolenItems";

	public static class Record {
		public String itemId;
		public String itemData;
		public String industryId;      // null if held in storage rather than installed
		public String holderMarketId;
		public String origMarketName;
		public String factionId;
	}

	@SuppressWarnings("unchecked")
	public static List<Record> all() {
		Object val = Global.getSector().getPersistentData().get(KEY);
		if (!(val instanceof List)) {
			val = new ArrayList<Record>();
			Global.getSector().getPersistentData().put(KEY, val);
		}
		return (List<Record>) val;
	}

	public static void add(Record record) {
		all().add(record);
	}

	public static List<Record> forMarket(String marketId) {
		List<Record> result = new ArrayList<Record>();
		for (Record r : all()) {
			if (marketId.equals(r.holderMarketId)) result.add(r);
		}
		return result;
	}

	public static void remove(Record record) {
		all().remove(record);
	}
}
