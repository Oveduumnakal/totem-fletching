package com.github.therealguru.totemfletching.service;

import com.github.therealguru.totemfletching.TotemFletchingConfig;
import com.github.therealguru.totemfletching.model.Totem;
import com.github.therealguru.totemfletching.model.TotemRegion;
import com.github.therealguru.totemfletching.model.TotemSite;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

/**
 * Shows a route to the next totem that needs work through the Shortest Path plugin.
 *
 * <p>Only {@link PluginMessage}s are posted, so nothing happens when Shortest Path is not
 * installed. A route is sent when the target changes, never every tick, and only a route sent by
 * this service is cleared.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ShortestPathService {

    private static final String NAMESPACE = "shortestpath";
    private static final String PATH = "path";
    private static final String CLEAR = "clear";
    private static final int MAX_SITE_DRIFT = 3;

    private final EventBus eventBus;
    private final TotemFletchingConfig config;
    private final TotemService totemService;

    private WorldPoint sentTarget;
    private boolean wasInside;

    /**
     * Sends a route to the nearest totem that is not fully decorated, or clears the route when
     * there is none or the player is outside Auburnvale. Does nothing if the setting is off or the
     * target has not changed since the last route was sent.
     *
     * @param player the player's current tile, or null if not yet known
     */
    public void update(final WorldPoint player) {
        if (!config.useShortestPath() || player == null) {
            return;
        }
        if (!TotemRegion.isInsideAuburnvale(player)) {
            clear();
            return;
        }
        Optional<WorldPoint> target = selectTarget(player, totemService.getTotems());
        if (target.isEmpty()) {
            clear();
            return;
        }
        if (target.get().equals(sentTarget)) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("start", player);
        data.put("target", target.get());
        eventBus.post(new PluginMessage(NAMESPACE, PATH, data));
        sentTarget = target.get();
    }

    /** Removes the route if this service sent one. */
    public void clear() {
        if (sentTarget == null) {
            return;
        }
        eventBus.post(new PluginMessage(NAMESPACE, CLEAR));
        sentTarget = null;
    }

    /** Removes the route and forgets which side of Auburnvale the player was on. */
    public void shutDown() {
        clear();
        wasInside = false;
    }

    /**
     * Sends or clears the route when the player walks into or out of Auburnvale. Sends nothing
     * while the player stays on the same side of the boundary.
     *
     * @param player the player's current tile, or null if not yet known
     */
    public void onGameTick(final WorldPoint player) {
        if (player == null) {
            return;
        }
        boolean inside = TotemRegion.isInsideAuburnvale(player);
        if (inside == wasInside) {
            return;
        }
        wasInside = inside;
        if (inside) {
            update(player);
        } else {
            clear();
        }
    }

    /**
     * Picks the nearest totem that is not fully decorated.
     *
     * @param player the player's current tile
     * @param totems all totems
     * @return the tile of the nearest totem that needs work, or empty if none does
     */
    static Optional<WorldPoint> selectTarget(final WorldPoint player, final List<Totem> totems) {
        return totems.stream()
                .filter(totem -> !totem.isDecorated())
                .map(ShortestPathService::locationOf)
                .flatMap(Optional::stream)
                .min(Comparator.comparingInt(location -> location.distanceTo2D(player)));
    }

    private static Optional<WorldPoint> locationOf(final Totem totem) {
        Optional<TotemSite> site = TotemSite.forTotemId(totem.getTotemId());
        GameObject live = totem.getTotemGameObject();
        if (live == null) {
            return site.map(TotemSite::getLocation);
        }
        WorldPoint liveLocation = live.getWorldLocation();
        if (site.isPresent()
                && liveLocation.distanceTo2D(site.get().getLocation()) > MAX_SITE_DRIFT) {
            log.warn(
                    "Totem {} is at {} but the site table says {}",
                    totem.getTotemId(),
                    liveLocation,
                    site.get().getLocation());
        }
        return Optional.of(liveLocation);
    }
}
