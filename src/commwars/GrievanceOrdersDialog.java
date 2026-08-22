package commwars;

import java.awt.Color;
import java.util.Map;

import org.lwjgl.input.Keyboard;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.ui.IntelUIAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The "respond to the ultimatum" dialog, opened from the grievance intel
 * screen. The faction accepts one-off settlement payments that buy down
 * accumulated resentment - partial installments, or a full settlement that
 * clears the ledger. Money is taken up front; there is no recurring
 * arrangement to lapse or exploit.
 *
 * Compliance is not an option here on purpose: it is a physical act (reduce
 * your market share), not a stance.
 */
public class GrievanceOrdersDialog implements InteractionDialogPlugin {

	public static final int SMALL_POINTS = 100;
	public static final int LARGE_POINTS = 250;

	private static enum OptionId {
		INIT,
		SETTLE_SMALL,
		SETTLE_LARGE,
		SETTLE_FULL,
		COMMISSION,
		CONFIRM_SETTLE,
		CONFIRM_COMMISSION,
		CANCEL,
		LEAVE,
	}

	protected InteractionDialogAPI dialog;
	protected TextPanelAPI textPanel;
	protected OptionPanelAPI options;
	protected CampaignFleetAPI playerFleet;

	protected GrievanceEventIntel intel;
	protected IntelUIAPI ui;

	protected int pendingPoints = 0;
	protected int pendingCost = 0;

	public GrievanceOrdersDialog(GrievanceEventIntel intel, IntelUIAPI ui) {
		this.intel = intel;
		this.ui = ui;
	}

	@Override
	public void init(InteractionDialogAPI dialog) {
		this.dialog = dialog;
		textPanel = dialog.getTextPanel();
		options = dialog.getOptionPanel();
		playerFleet = Global.getSector().getPlayerFleet();

		dialog.setOptionOnEscape("Leave", OptionId.LEAVE);

		optionSelected(null, OptionId.INIT);
	}

	protected void printStatus() {
		Color h = Misc.getHighlightColor();
		String factionName = intel.getFaction().getDisplayNameWithArticle();

		boolean trade = !intel.getCauseCommodityIds().isEmpty();
		boolean military = intel.getMilitaryCause() != null;

		if (trade && military) {
			textPanel.addPara(Misc.ucFirst(factionName) + " demands that you scale back your "
					+ "exports of the contested commodities (%s) and your military buildup.",
					h, intel.getContestedCommodityNames());
		} else if (military) {
			textPanel.addPara(Misc.ucFirst(factionName) + " demands that you scale back your "
					+ "military buildup.");
		} else {
			textPanel.addPara(Misc.ucFirst(factionName) + " demands that you scale back your "
					+ "exports of the contested commodities: %s.",
					h, intel.getContestedCommodityNames());
		}

		textPanel.addPara("They will, however, accept reparations: one-off settlement payments "
				+ "that soothe accumulated resentment. The rate tracks "
				+ (military && !trade ? "the scale of your arsenal"
						: "your contested export income")
				+ " - buying peace costs in proportion to the dominance that caused "
				+ "the dispute.");

		textPanel.addPara("There is no need to formally announce compliance: the trade ledgers speak "
				+ "for themselves. Downgrade or demolish the producing industries - or otherwise lose "
				+ "market share - and the dispute will cool on its own.");

		if (intel.getMilitaryCause() != null) {
			textPanel.addPara("Your military buildup is also contested. A commission with "
					+ factionName + " would place your arsenal nominally under "
					+ (intel.getFaction().getDisplayNameIsOrAre().equals("are") ? "their" : "its")
					+ " flag, laying that concern to rest for as long as you serve.", h,
					"commission");
		}
	}

	protected void addChoiceOptions() {
		options.clearOptions();

		// the strike may have launched while this dialog was open
		if (intel.isStrikeActive()) {
			textPanel.addPara("Enforcement fleets are underway - the time for negotiation "
					+ "has passed.", Misc.getNegativeHighlightColor());
			options.addOption("Dismiss", OptionId.LEAVE, null);
			options.setShortcut(OptionId.LEAVE, Keyboard.KEY_ESCAPE, false, false, false, true);
			return;
		}

		int progress = intel.getProgress();
		float credits = playerFleet.getCargo().getCredits().get();

		if (progress <= 0) {
			textPanel.addPara("There is no outstanding resentment to settle.",
					Misc.getPositiveHighlightColor());
		} else {
			if (progress > SMALL_POINTS) {
				addSettleOption(OptionId.SETTLE_SMALL, "Offer a partial settlement",
						SMALL_POINTS, credits);
			}
			if (progress > LARGE_POINTS) {
				addSettleOption(OptionId.SETTLE_LARGE, "Offer a substantial settlement",
						LARGE_POINTS, credits);
			}
			addSettleOption(OptionId.SETTLE_FULL, "Settle in full", progress, credits);
		}

		if (intel.getMilitaryCause() != null) {
			options.addOption("Take a " + intel.getFaction().getDisplayName()
					+ " commission - place your arsenal under their flag", OptionId.COMMISSION, null);
			String commissionedWith = Misc.getCommissionFactionId();
			if (commissionedWith != null) {
				options.setEnabled(OptionId.COMMISSION, false);
				options.setTooltip(OptionId.COMMISSION, "You already hold a commission with "
						+ Global.getSector().getFaction(commissionedWith).getDisplayNameWithArticle()
						+ " - you cannot serve two flags.");
			} else if (!intel.getFaction().getRelToPlayer().getLevel()
					.isAtWorst(com.fs.starfarer.api.campaign.RepLevel.NEUTRAL)) {
				options.setEnabled(OptionId.COMMISSION, false);
				options.setTooltip(OptionId.COMMISSION,
						"Your standing with them is too poor for a commission.");
			}
		}

		options.addOption("Dismiss", OptionId.LEAVE, null);
		options.setShortcut(OptionId.LEAVE, Keyboard.KEY_ESCAPE, false, false, false, true);
	}

	protected void addSettleOption(OptionId id, String label, int points, float credits) {
		int cost = TributeCalc.costFor(intel, points);
		String desc = id == OptionId.SETTLE_FULL
				? label + ": clear all resentment for " + Misc.getDGSCredits(cost)
				: label + ": reduce resentment by " + points + " for " + Misc.getDGSCredits(cost);
		options.addOption(desc, id, null);
		if (credits < cost) {
			options.setEnabled(id, false);
			options.setTooltip(id, "Not enough credits.");
		}
	}

	protected void addConfirmOptions() {
		options.clearOptions();

		Color h = Misc.getHighlightColor();
		textPanel.addPara("The payment of %s will be made immediately, reducing resentment "
				+ "by %s. The dispute itself remains: as long as your exports keep crowding "
				+ "theirs, resentment will build again.", h,
				Misc.getDGSCredits(pendingCost), "" + pendingPoints);

		options.addOption("Confirm", OptionId.CONFIRM_SETTLE, null);
		options.addOption("Never mind", OptionId.CANCEL, null);
		options.setShortcut(OptionId.CANCEL, Keyboard.KEY_ESCAPE, false, false, false, true);
	}

	@Override
	public void optionSelected(String text, Object optionData) {
		if (optionData == null) return;
		OptionId option = (OptionId) optionData;

		if (text != null) {
			dialog.addOptionSelectedText(option);
		}

		switch (option) {
			case INIT:
				printStatus();
				addChoiceOptions();
				break;
			case COMMISSION: {
				options.clearOptions();
				textPanel.addPara("Your fleet and arsenal will fly "
						+ intel.getFaction().getDisplayNameWithArticle() + "'s colors: a monthly "
						+ "stipend, access to their military markets - and their wars become "
						+ "yours. Their concern over your military ends for as long as you "
						+ "serve. Resigning the commission later will not be forgotten.");
				options.addOption("Confirm", OptionId.CONFIRM_COMMISSION, null);
				options.addOption("Never mind", OptionId.CANCEL, null);
				options.setShortcut(OptionId.CANCEL, Keyboard.KEY_ESCAPE, false, false, false, true);
				break;
			}
			case CONFIRM_COMMISSION: {
				if (Misc.getCommissionFactionId() != null) {
					textPanel.addPara("You already hold a commission.",
							Misc.getNegativeHighlightColor());
					addChoiceOptions();
					break;
				}
				com.fs.starfarer.api.impl.campaign.intel.FactionCommissionIntel commission =
						new com.fs.starfarer.api.impl.campaign.intel.FactionCommissionIntel(
								intel.getFaction());
				commission.missionAccepted();
				intel.setMilitaryCause(null);
				textPanel.addPara("Commission accepted. Your arsenal now flies "
						+ intel.getFaction().getDisplayNameWithArticle() + "'s colors.",
						Misc.getPositiveHighlightColor());
				CommWarsConfig.log("Player took a commission with " + intel.getFactionId()
						+ " to settle the military grievance");
				addChoiceOptions();
				break;
			}
			case SETTLE_SMALL:
			case SETTLE_LARGE:
			case SETTLE_FULL:
				pendingPoints = option == OptionId.SETTLE_SMALL ? SMALL_POINTS
						: option == OptionId.SETTLE_LARGE ? LARGE_POINTS
						: intel.getProgress();
				pendingPoints = Math.min(pendingPoints, intel.getProgress());
				pendingCost = TributeCalc.costFor(intel, pendingPoints);
				addConfirmOptions();
				break;
			case CONFIRM_SETTLE: {
				if (playerFleet.getCargo().getCredits().get() < pendingCost
						|| pendingPoints > intel.getProgress()) {
					// stale amounts (bar moved / credits spent); re-quote
					textPanel.addPara("The situation has changed - the offer is no longer on "
							+ "the table.", Misc.getNegativeHighlightColor());
					addChoiceOptions();
					break;
				}
				playerFleet.getCargo().getCredits().subtract(pendingCost);
				intel.paySettlement(pendingPoints, pendingCost);
				textPanel.addPara("Settlement of " + Misc.getDGSCredits(pendingCost)
						+ " paid. Resentment reduced by " + pendingPoints + ".",
						Misc.getPositiveHighlightColor());
				CommWarsConfig.log("Player settled " + pendingPoints + " points with "
						+ intel.getFactionId() + " for " + pendingCost);
				addChoiceOptions();
				break;
			}
			case CANCEL:
				addChoiceOptions();
				break;
			case LEAVE:
				dialog.dismiss();
				if (ui != null) ui.updateUIForItem(intel);
				break;
		}
	}

	@Override
	public void optionMousedOver(String optionText, Object optionData) {
	}

	@Override
	public void advance(float amount) {
	}

	@Override
	public void backFromEngagement(EngagementResultAPI battleResult) {
	}

	@Override
	public Object getContext() {
		return null;
	}

	@Override
	public Map<String, MemoryAPI> getMemoryMap() {
		return null;
	}
}
