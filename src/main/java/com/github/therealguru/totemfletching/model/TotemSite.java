package com.github.therealguru.totemfletching.model;

import java.util.Optional;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * Fixed world location of each of the eight totem sites in Auburnvale, in site order.
 *
 * <p>The live totem {@link net.runelite.api.GameObject} only exists while its site is inside the
 * loaded scene, so routes to a distant site need these tiles.
 */
@Getter
public enum TotemSite {
    SITE_1(new WorldPoint(1370, 3375, 0)),
    SITE_2(new WorldPoint(1346, 3319, 0)),
    SITE_3(new WorldPoint(1385, 3274, 0)),
    SITE_4(new WorldPoint(1413, 3286, 0)),
    SITE_5(new WorldPoint(1438, 3305, 0)),
    SITE_6(new WorldPoint(1477, 3332, 0)),
    SITE_7(new WorldPoint(1453, 3341, 0)),
    SITE_8(new WorldPoint(1398, 3329, 0));

    private final WorldPoint location;

    TotemSite(WorldPoint location) {
        this.location = location;
    }

    /**
     * Looks up the site for a totem id.
     *
     * @param totemId the totem id, from 1 to 8
     * @return the matching site, or empty if the id is out of range
     */
    public static Optional<TotemSite> forTotemId(int totemId) {
        if (totemId < 1 || totemId > values().length) {
            return Optional.empty();
        }
        return Optional.of(values()[totemId - 1]);
    }
}
