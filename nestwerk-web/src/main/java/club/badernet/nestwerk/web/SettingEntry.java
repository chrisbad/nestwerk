package club.badernet.nestwerk.web;

/** A setting as listed in the register: the descriptor together with its current value. */
public record SettingEntry(SettingDescriptor descriptor, SettingValue value) {

    public String id() {
        return descriptor.id();
    }

    public String title() {
        return descriptor.title();
    }

    public String description() {
        return descriptor.description();
    }

    public SettingArea area() {
        return descriptor.area();
    }

    public SettingScope scope() {
        return descriptor.scope();
    }

    public String path() {
        return descriptor.path();
    }

    public String summary() {
        return value.summary();
    }

    public SettingStatus status() {
        return value.status();
    }
}
