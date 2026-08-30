package commwars;

import java.awt.Color;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * A persistent after-action report for one enforcement action: the forces
 * committed, the ground assault result (raid strength vs defender strength),
 * and the actual consequences - so the damage (or the successful defense) does
 * not vanish with a scrolling feed message, and the outcome is legible in a
 * way the vanilla FGI "Success" line is not.
 */
public class StrikeSummaryIntel extends BaseIntelPlugin {

	protected String factionId;
	protected String targetName;

	protected boolean broke;          // did they break the ground defenses
	protected int attackerFleets;
	protected int attackerRaidStr;
	protected int defenderStr;

	protected String disruptSummary;  // industries disrupted (broke through)
	protected int disruptDays;
	protected boolean bombarded;      // a bombardment landed - not gated on the ground break
	protected boolean saturation;
	protected String heistItemName;

	public StrikeSummaryIntel(String factionId, String targetName) {
		this.factionId = factionId;
		this.targetName = targetName;
		// NOT added to the intel manager here: the feed message renders the
		// name at add-time, and the outcome fields are only set after
		// construction - adding now titles it "Enforcement Repelled" (the
		// default state) even for a strike that broke through. finish() adds
		// it once the report is complete.
	}

	public void finish() {
		Global.getSector().getIntelManager().addIntel(this, false);
		endAfterDelay(CommWarsConfig.strikeSummaryDays());
	}

	public FactionAPI getFaction() {
		return Global.getSector().getFaction(factionId);
	}

	public void setOutcome(boolean broke, int fleets, int raidStr, int defenderStr) {
		this.broke = broke;
		this.attackerFleets = fleets;
		this.attackerRaidStr = raidStr;
		this.defenderStr = defenderStr;
	}

	public void setDisrupt(String summary, int days) {
		this.disruptSummary = summary;
		this.disruptDays = days;
	}

	public void setBombarded(boolean b, boolean saturation) {
		this.bombarded = b;
		this.saturation = saturation;
	}
	public void setHeist(String itemName) { this.heistItemName = itemName; }

	protected java.util.List<String> operations;

	public void setOperations(java.util.List<String> operations) {
		this.operations = operations;
	}

	protected boolean anyOperation(String marker) {
		if (operations == null) return false;
		for (String op : operations) {
			if (op.contains(marker)) return true;
		}
		return false;
	}

	@Override
	protected float getBaseDaysAfterEnd() {
		return CommWarsConfig.strikeSummaryDays();
	}

	@Override
	public String getName() {
		// a bombardment lands whether or not the ground assault broke through,
		// so a repelled-but-bombarded strike still did damage
		String kind = (broke || bombarded) ? "Enforcement Damage" : "Enforcement Repelled";
		return kind + " - " + getFaction().getDisplayName()
				+ (targetName != null ? " vs " + targetName : "");
	}

	@Override
	public String getIcon() {
		String crest = getFaction().getCrest();
		return crest != null ? crest : super.getIcon();
	}

	@Override
	public boolean hasSmallDescription() {
		return true;
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color bad = Misc.getNegativeHighlightColor();
		Color good = Misc.getPositiveHighlightColor();
		FactionAPI faction = getFaction();
		String target = targetName != null ? targetName : "your colony";

		// forces
		info.addPara(Misc.ucFirst(faction.getDisplayNameWithArticle()) + " committed %s fleet"
				+ (attackerFleets == 1 ? "" : "s") + " against " + target + ".", opad,
				h, "" + attackerFleets);

		// the ground assault
		float ratio = defenderStr > 0 ? (float) attackerRaidStr / defenderStr : 99f;
		info.addPara("Ground assault: raid strength %s against your defender strength %s "
				+ "(including any planetary shield and garrison marines) - a %s-to-1 ratio.",
				3f, h, Misc.getWithDGS(attackerRaidStr), Misc.getWithDGS(defenderStr),
				Misc.getRoundedValueMaxOneAfterDecimal(ratio));

		if (broke) {
			info.addPara("The raiders broke through. Casualties among the attacking marines were "
					+ (ratio >= 2f ? "light - they came in overwhelming force."
						: "heavy - they barely carried the assault."), 3f,
					ratio >= 2f ? h : good, ratio >= 2f ? "light" : "heavy");
			if (anyOperation("repelled")) {
				info.addPara("The breakthrough was not clean: fleets landed piecemeal, and "
						+ "individual landings were thrown back before the defenses buckled.",
						3f, good, "thrown back");
			}

			info.setBulletedListMode(BaseIntelPlugin.INDENT);
			if (disruptSummary != null) {
				info.addPara("Industries disrupted for %s days: " + disruptSummary, 8f, bad,
						"" + disruptDays);
				info.addPara("- production of those goods stops; your market share on them "
						+ "falls until they recover", 3f);
			}
			if (bombarded) {
				info.addPara(saturation
						? "Saturation bombardment - catastrophic damage to the colony"
						: "Tactical bombardment - military infrastructure disrupted, "
							+ "stability hit", 8f, bad, "");
			}
			if (heistItemName != null) {
				info.addPara("Strategic asset seized: " + heistItemName
						+ " - carried off, and recoverable by raiding them back", 8f, bad, "");
			}
			info.setBulletedListMode(null);
		} else if (bombarded) {
			// ground defenses gate the landing, not the shells: the bombardment
			// landed even though the assault was repelled
			info.addPara("Your ground defenses held - the landing force was repelled, and "
					+ "nothing was taken on the ground.", opad, good, "repelled");
			info.addPara(saturation
					? "But no ground defense stops the shells: their saturation bombardment "
						+ "struck home, devastating the colony."
					: "But no ground defense stops the shells: their tactical bombardment "
						+ "struck home - military infrastructure disrupted, stability hit.",
					3f, bad, saturation ? "saturation bombardment" : "tactical bombardment");
			info.addPara("They withdraw, and their resentment - undented - will send them back "
					+ "in greater force.", 3f);
		} else if (anyOperation("disrupted")) {
			// the massed assault failed, but individual landings still landed blows
			info.addPara("Your ground defenses held against the main assault - but the raiders "
					+ "came piecemeal, and some landings did real damage before withdrawing "
					+ "(see the operations report below).", opad, good, "held");
			info.addPara("They withdraw, and will return in greater force.", 3f);
		} else {
			info.addPara("Your ground defenses held - the assault was repelled. Nothing was "
					+ "taken, nothing disrupted, and the attacking fleets took losses forcing "
					+ "the failed landing.", opad, good, "repelled");
			info.addPara("They withdraw, and their resentment - undented - will send them back "
					+ "in greater force.", 3f);
		}

		// the fleet-by-fleet ground truth, so this report and the raid intel's
		// operations log can never tell different stories
		if (operations != null && !operations.isEmpty()) {
			info.addSectionHeading("Operations report", faction.getBaseUIColor(),
					faction.getDarkUIColor(), com.fs.starfarer.api.ui.Alignment.MID, opad);
			for (String entry : operations) {
				boolean hurt = entry.contains("bombardment") || entry.contains("disrupted")
						|| entry.contains("stockpiles");
				info.addPara(BULLET + entry, hurt ? bad : good, 3f);
			}
		}
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = super.getIntelTags(map);
		tags.add(Tags.INTEL_MILITARY);
		tags.add(factionId);
		return tags;
	}

	@Override
	public String getSortString() {
		return "Enforcement";
	}
}
