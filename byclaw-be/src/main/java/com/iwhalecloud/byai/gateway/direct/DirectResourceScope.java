package com.iwhalecloud.byai.gateway.direct;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The resource grants a Direct runtime may see. The platform keeps one grant hash per user across
 * every enterprise; a runtime belongs to one enterprise security domain, so it reads a copy that
 * holds only the grants for resources of that enterprise. The copy is rebuilt before the runtime
 * starts and on every idle sweep, so a revoked grant disappears there too; the runtime's resource
 * watch follows the key. A {@code scope} field keeps the hash present when nothing is granted.
 */
public class DirectResourceScope {

    static final String USER_GRANTS = "USER:RESOURCES:AUTH:";

    /** Where grant hashes live; {@link #replace} swaps the whole hash at once. */
    public interface Store {
        Map<String, String> read(String key);

        void replace(String key, Map<String, String> entries);
    }

    private final Store store;
    private final Function<Collection<String>, Map<String, Long>> enterprises;

    /** @param enterprises resource id to its enterprise, for the given ids that still exist */
    public DirectResourceScope(Store store, Function<Collection<String>, Map<String, Long>> enterprises) {
        this.store = store;
        this.enterprises = enterprises;
    }

    public static String key(RuntimeOwnerScope owner) {
        return USER_GRANTS + owner.userId() + ":E" + owner.enterpriseId();
    }

    private static final java.util.regex.Pattern OWNER_KEY = java.util.regex.Pattern.compile("u(\\d+)\\.e(\\d+)");

    /** Refresh by owner key ({@code u<user>.e<enterprise>}), as listed for ready runtimes. */
    public boolean refresh(String ownerKey) {
        java.util.regex.Matcher key = OWNER_KEY.matcher(ownerKey);
        if (!key.matches()) return false;
        return refresh(new RuntimeOwnerScope(Long.parseLong(key.group(1)), ownerKey, Long.parseLong(key.group(2))));
    }

    /** @return true when the owner's copy changed. */
    public boolean refresh(RuntimeOwnerScope owner) {
        Map<String, String> grants = store.read(USER_GRANTS + owner.userId());
        Map<String, Long> owning = grants.isEmpty() ? Map.of() : enterprises.apply(grants.keySet());
        Map<String, String> scoped = new LinkedHashMap<>();
        grants.forEach((resourceId, type) -> {
            if (Objects.equals(owning.get(resourceId), owner.enterpriseId())) scoped.put(resourceId, type);
        });
        scoped.put("scope", owner.key());
        String key = key(owner);
        if (scoped.equals(store.read(key))) return false;
        store.replace(key, scoped);
        return true;
    }
}
