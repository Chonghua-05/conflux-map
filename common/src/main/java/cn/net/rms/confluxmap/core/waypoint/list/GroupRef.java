package cn.net.rms.confluxmap.core.waypoint.list;

import java.util.Objects;

/**
 * One group bucket in the unified waypoint list template. The local model
 * keys groups by set name, the shared model by group UUID string; keys are
 * only ever compared inside the model that produced them. The reserved
 * {@link #UNGROUPED_KEY} bucket exists in both models and always sorts last.
 *
 * @param key         stable identity of the group within its model
 * @param displayName raw display name; the ungrouped bucket carries the empty
 *                    string and the screen substitutes the localized label
 * @param collapsed   persisted fold state of the list header
 */
public record GroupRef(String key, String displayName, boolean collapsed) {
    public static final String UNGROUPED_KEY = "";

    public GroupRef {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
    }

    public static GroupRef ungrouped(final boolean collapsed) {
        return new GroupRef(UNGROUPED_KEY, "", collapsed);
    }

    public boolean isUngrouped() {
        return UNGROUPED_KEY.equals(key);
    }
}
