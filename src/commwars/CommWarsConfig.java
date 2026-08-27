package commwars;

import com.fs.starfarer.api.Global;

/**
 * Central settings accessor: LunaLib in-game menu when available, bundled
 * data/config/settings.json as the standalone fallback.
 */
public class CommWarsConfig {

	public static final String MOD_ID = "commwars";

	private static Boolean lunaEnabled = null;

	public static boolean lunaAvailable() {
		if (lunaEnabled == null) {
			lunaEnabled = Global.getSettings().getModManager().isModEnabled("lunalib");
		}
		return lunaEnabled;
	}

	private static int i(String key) {
		if (lunaAvailable()) {
			Integer v = LunaConfigBridge.getInt(key);
			if (v != null) return v;
		}
		return (int) Global.getSettings().getFloat(key);
	}

	private static float f(String key) {
		if (lunaAvailable()) {
			Float v = LunaConfigBridge.getFloat(key);
			if (v != null) return v;
		}
		return Global.getSettings().getFloat(key);
	}

	private static boolean b(String key, boolean def) {
		if (lunaAvailable()) {
			Boolean v = LunaConfigBridge.getBoolean(key);
			if (v != null) return v;
		}
		try {
			return Global.getSettings().getBoolean(key);
		} catch (Throwable t) {
			return def;
		}
	}

	// ---- master & clock ----

	public static boolean enabled()             { return b("commwars_enabled", true); }
	public static float tickDays()              { return f("commwars_tickDays"); }

	// ---- grievance detection ----

	public static int minPlayerShare()          { return i("commwars_minPlayerShare"); }
	public static int minFactionShare()         { return i("commwars_minFactionShare"); }
	public static int topProducers()            { return i("commwars_topProducers"); }
	public static float noticeFraction()        { return f("commwars_noticeFraction"); }

	// ---- resentment accrual ----

	public static float weightMult()            { return f("commwars_weightMult"); }
	public static int maxMonthlyPerCommodity()  { return i("commwars_maxMonthlyPerCommodity"); }
	public static float startThreshold()        { return f("commwars_startThreshold"); }
	public static int maxActiveGrievances()     { return i("commwars_maxActiveGrievances"); }

	// ---- cooling off ----

	public static int decayPerMonth()           { return i("commwars_decayPerMonth"); }
	public static float endAfterCalmDays()      { return f("commwars_endAfterCalmDays"); }

	// ---- settlements ----

	public static float settleMonths()          { return f("commwars_settleMonths"); }
	public static int settleMinimum()           { return i("commwars_settleMinimum"); }
	public static float settleMilPerScore()     { return f("commwars_settleMilPerScore"); }

	// ---- military track ----

	public static float milNoticeFraction()     { return f("commwars_milNoticeFraction"); }
	public static int milMinPlayerScore()       { return i("commwars_milMinPlayerScore"); }
	public static float milWeightMult()         { return f("commwars_milWeightMult"); }
	public static int maxMonthlyMilitary()      { return i("commwars_maxMonthlyMilitary"); }
	public static int commissionLapseSpike()    { return i("commwars_commissionLapseSpike"); }

	// ---- enforcement ----

	public static float strikeBaseDifficulty()  { return f("commwars_strikeBaseDifficulty"); }
	public static float strikeEscalationMult()  { return f("commwars_strikeEscalationMult"); }
	public static float strikeMaxDifficulty()   { return f("commwars_strikeMaxDifficulty"); }
	public static float overmatch()             { return f("commwars_overmatch"); }
	public static float strengthPerDifficulty() { return f("commwars_strengthPerDifficulty"); }
	public static float capacityMult()          { return f("commwars_capacityMult"); }
	public static int strikeResetProgress()     { return i("commwars_strikeResetProgress"); }
	public static int strikeDefeatReset()       { return i("commwars_strikeDefeatReset"); }
	public static float strikeCooldownDays()    { return f("commwars_strikeCooldownDays"); }
	public static float truceDaysAfterStrike()  { return f("commwars_truceDaysAfterStrike"); }
	public static int tacBombEscalation()       { return i("commwars_tacBombEscalation"); }
	public static float stealFraction()         { return f("commwars_stealFraction"); }
	public static float plunderDays()           { return f("commwars_plunderDays"); }
	public static int plunderPenalty()          { return i("commwars_plunderPenalty"); }

	// ---- item heists ----

	public static int heistEscalation()         { return i("commwars_heistEscalation"); }
	public static float heistOvermatch()        { return f("commwars_heistOvermatch"); }
	public static float marinesPerSupply()      { return f("commwars_marinesPerSupply"); }
	public static float heistMusterDays()       { return f("commwars_heistMusterDays"); }
	public static int heistMusterPenalty()      { return i("commwars_heistMusterPenalty"); }
	public static float musterCasualtyFraction(){ return f("commwars_musterCasualtyFraction"); }

	// ---- strength gate & coalitions ----

	public static boolean gateEnabled()         { return b("commwars_gateEnabled", true); }
	public static boolean deferToVanillaCrises(){ return b("commwars_deferToVanillaCrises", true); }
	public static boolean totalWar()            { return b("commwars_totalWar", false); }

	// ---- vendetta ----

	public static boolean vendettaEnabled()     { return b("commwars_vendettaEnabled", true); }
	public static int vendettaStartProgress()   { return i("commwars_vendettaStartProgress"); }
	public static int vendettaPerMonth()        { return i("commwars_vendettaPerMonth"); }
	public static float vendettaTempoMult()     { return f("commwars_vendettaTempoMult"); }
	public static float gateRatio()             { return f("commwars_gateRatio"); }
	public static float coalitionDropoutChance(){ return f("commwars_coalitionDropoutChance"); }
	public static float coalitionDropoutDays()  { return f("commwars_coalitionDropoutDays"); }
	public static int coalitionMinAnger()       { return i("commwars_coalitionMinAnger"); }

	// ---- retaliation (phase 6) ----

	public static int retalRaidSpike()          { return i("commwars_retalRaidSpike"); }
	public static int retalTacBombSpike()       { return i("commwars_retalTacBombSpike"); }
	public static int retalSatBombSpike()       { return i("commwars_retalSatBombSpike"); }
	public static int atrocityPeerSpike()       { return i("commwars_atrocityPeerSpike"); }

	// ---- debug ----

	public static boolean debugMode()           { return b("commwars_debugMode", false); }
	public static boolean debugLogging()        { return b("commwars_debugLogging", false); }
	public static boolean debugFastClock()      { return b("commwars_debugFastClock", false); }
	public static boolean debugForceGrievance() { return b("commwars_debugForceGrievance", false); }
	public static boolean debugForceUltimatum() { return b("commwars_debugForceUltimatum", false); }
	public static boolean debugForceStrike()    { return b("commwars_debugForceStrike", false); }

	/** Accrual multiplier applied on top of normal weights. */
	public static float clockMult() {
		return debugFastClock() ? 10f : 1f;
	}

	public static void log(String msg) {
		if (debugLogging()) {
			Global.getLogger(CommWarsConfig.class).info("[CommWars] " + msg);
		}
	}
}
