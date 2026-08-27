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
 * A persistent, readable record of what one enforcement action actually cost
 * the player - stolen goods, industries disrupted, credit value - so the
 * damage does not vanish with a scrolling feed message. Expires after a while.
 */
public class StrikeSummaryIntel extends BaseIntelPlugin {

	protected String factionId;
	protected String targetName;
	protected String lootSummary;    // e.g. "5,906 supplies, 5,062 organics"
	protected int lootValue;         // approx credits
	protected String disruptSummary; // e.g. "Farming, Mining"
	protected int disruptDays;
	protected boolean bombarded;
	protected boolean heist;
	protected String heistItemName;

	public StrikeSummaryIntel(String factionId, String targetName) {
		this.factionId = factionId;
		this.targetName = targetName;
		Global.getSector().getIntelManager().addIntel(this, false);
	}

	/** Call once all fields are set: keeps the record for the configured window. */
	public void finish() {
		endAfterDelay(CommWarsConfig.strikeSummaryDays());
	}

	public FactionAPI getFaction() {
		return Global.getSector().getFaction(factionId);
	}

	public void setLoot(String summary, int value) {
		this.lootSummary = summary;
		this.lootValue = value;
	}

	public void setDisrupt(String summary, int days) {
		this.disruptSummary = summary;
		this.disruptDays = days;
	}

	public void setBombarded(boolean b) { this.bombarded = b; }
	public void setHeist(String itemName) { this.heist = true; this.heistItemName = itemName; }

	@Override
	protected float getBaseDaysAfterEnd() {
		return CommWarsConfig.strikeSummaryDays();
	}

	@Override
	public String getName() {
		return "Enforcement Damage - " + getFaction().getDisplayName()
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
		FactionAPI faction = getFaction();

		info.addPara(Misc.ucFirst(faction.getDisplayNameWithArticle())
				+ " enforcement fleets struck " + (targetName != null ? targetName : "your colony")
				+ ". The damage they did:", opad);

		info.setBulletedListMode(BaseIntelPlugin.INDENT);
		if (disruptSummary != null) {
			info.addPara("Industries disrupted for %s days: " + disruptSummary, 5f, bad,
					"" + disruptDays);
			info.addPara("- production of those goods stops, and your market share on them "
					+ "falls until they recover", 3f);
		}
		if (lootSummary != null) {
			info.addPara("Stockpiles seized: " + lootSummary
					+ (lootValue > 0 ? " (~" + Misc.getDGSCredits(lootValue) + ")" : ""), 5f, bad, "");
		}
		if (bombarded) {
			info.addPara("Tactical bombardment - military infrastructure disrupted, stability hit",
					5f, bad, "");
		}
		if (heist && heistItemName != null) {
			info.addPara("Strategic asset seized: " + heistItemName
					+ " - carried off, and recoverable by raiding them back", 5f, bad, "");
		}
		info.setBulletedListMode(null);

		if (disruptSummary == null && lootSummary == null && !bombarded && !heist) {
			info.addPara("Your defenses held - the enforcement action achieved little of lasting "
					+ "consequence.", opad, Misc.getPositiveHighlightColor(), h, "");
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
		return "Enforcement Damage";
	}
}
