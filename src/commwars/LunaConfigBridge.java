package commwars;

import lunalib.lunaSettings.LunaSettings;

/**
 * Thin wrapper around LunaLib's settings API. This class references LunaLib
 * types directly, so it must ONLY be loaded when LunaLib is present - callers
 * gate every use behind {@link CommWarsConfig#lunaAvailable()}.
 */
class LunaConfigBridge {

	static Integer getInt(String key) {
		return LunaSettings.getInt(CommWarsConfig.MOD_ID, key);
	}

	static Float getFloat(String key) {
		return LunaSettings.getFloat(CommWarsConfig.MOD_ID, key);
	}

	static Boolean getBoolean(String key) {
		return LunaSettings.getBoolean(CommWarsConfig.MOD_ID, key);
	}
}
