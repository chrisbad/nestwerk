package club.badernet.nestwerk.web;

/**
 * Short form of the current state of a setting.
 *
 * @param summary text for the register, e.g. "6 Kategorien"; empty if there is nothing worth showing
 * @param status  whether the setting needs attention
 */
public record SettingValue(String summary, SettingStatus status) {

    static final SettingValue NONE = new SettingValue("", SettingStatus.OK);

    static SettingValue ok(String summary) {
        return new SettingValue(summary, SettingStatus.OK);
    }

    static SettingValue attention(String summary) {
        return new SettingValue(summary, SettingStatus.ATTENTION);
    }

    static SettingValue notSet(String summary) {
        return new SettingValue(summary, SettingStatus.NOT_SET);
    }
}
