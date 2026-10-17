package com.github.therealguru.totemfletching.service;

import com.github.therealguru.totemfletching.TotemFletchingConfig;
import com.github.therealguru.totemfletching.model.Totem;
import com.github.therealguru.totemfletching.model.TotemRegion;
import com.github.therealguru.totemfletching.model.TotemSite;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
 * <p>With ent trails enabled, the route first passes through the nearest pair of inactive ent trail
 * flowers. Shortest Path shows one path at a time, so the route is sent one stop at a time: the
 * next stop is sent when the current one is reached and its flower stops being inactive.
 *
 * <p>Only {@link PluginMessage}s are posted, so nothing happens when Shortest Path is not
 * installed. A route is sent when the next stop changes, never every tick, and only a route sent by
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
    private static final int MAX_PAIR_DISTANCE = 12;

    private final EventBus eventBus;
    private final TotemFletchingConfig config;
    private final TotemService totemService;
    private final EntTrailService entTrailService;

    private WorldPoint sentTarget;
    private boolean wasInside;
    private Set<WorldPoint> trailSnapshot = new HashSet<>();

    /**
     * Sends a route to the next stop, or clears the route when there is none or the player is
     * outside Auburnvale. Does nothing if the setting is off or the next stop has not changed since
     * the last route was sent.
     *
     * @param player the player's current tile, or null if not yet known
     */
    public void update(final WorldPoint player) {
        update(player, currentTrails());
    }

    void update(final WorldPoint player, final List<WorldPoint> trails) {
        if (!config.useShortestPath() || player == null) {
            return;
        }
        if (!TotemRegion.isInsideAuburnvale(player)) {
            clear();
            return;
        }
        List<WorldPoint> flowers = config.routeViaEntTrails() ? trails : List.of();
        List<WorldPoint> route = selectRoute(player, totemService.getTotems(), flowers);
        if (route.isEmpty()) {
            clear();
            return;
        }
        WorldPoint target = route.get(0);
        if (target.equals(sentTarget)) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("start", player);
        data.put("target", target);
        eventBus.post(new PluginMessage(NAMESPACE, PATH, data));
        sentTarget = target;
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
        trailSnapshot = new HashSet<>();
    }

    /**
     * Sends or clears the route when the player walks into or out of Auburnvale, and sends the next
     * stop when the set of inactive ent trails changes. Sends nothing otherwise.
     *
     * @param player the player's current tile, or null if not yet known
     */
    public void onGameTick(final WorldPoint player) {
        onGameTick(player, currentTrails());
    }

    void onGameTick(final WorldPoint player, final List<WorldPoint> trails) {
        if (player == null) {
            return;
        }
        boolean inside = TotemRegion.isInsideAuburnvale(player);
        boolean crossed = inside != wasInside;
        wasInside = inside;
        if (!inside) {
            if (crossed) {
                clear();
            }
            return;
        }
        boolean trailsChanged = trailsChanged(trails);
        if (crossed || trailsChanged) {
            update(player, trails);
        }
    }

    private boolean trailsChanged(final List<WorldPoint> trails) {
        if (!config.useShortestPath() || !config.routeViaEntTrails()) {
            return false;
        }
        Set<WorldPoint> current = new HashSet<>(trails);
        if (current.equals(trailSnapshot)) {
            return false;
        }
        trailSnapshot = current;
        return true;
    }

    private List<WorldPoint> currentTrails() {
        if (!config.useShortestPath() || !config.routeViaEntTrails()) {
            return List.of();
        }
        return entTrailService.getInactiveEntTrails().stream()
                .map(GameObject::getWorldLocation)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Builds the stops in order: the nearest pair of inactive ent trail flowers, if any, then the
     * nearest totem that is not fully decorated, measured from the last flower.
     *
     * @param player the player's current tile
     * @param totems all totems
     * @param trails the tiles of the inactive ent trail flowers
     * @return the stops in order, or empty if no totem needs work
     */
    static List<WorldPoint> selectRoute(
            final WorldPoint player, final List<Totem> totems, final List<WorldPoint> trails) {
        List<WorldPoint> pair = selectTrailPair(player, trails);
        WorldPoint origin = pair.isEmpty() ? player : pair.get(pair.size() - 1);
        Optional<WorldPoint> totem = selectTarget(origin, totems);
        if (totem.isEmpty()) {
            return List.of();
        }
        List<WorldPoint> route = new ArrayList<>(pair);
        route.add(totem.get());
        return route;
    }

    /**
     * Picks the inactive flower nearest the player and, if one is close enough to pair with it, the
     * flower nearest that one. A flower with no partner nearby is returned alone, which is what is
     * left once the first flower of a pair has been stepped on.
     *
     * @param player the player's current tile
     * @param trails the tiles of the inactive ent trail flowers
     * @return zero, one or two flower tiles in the order to visit them
     */
    static List<WorldPoint> selectTrailPair(
            final WorldPoint player, final List<WorldPoint> trails) {
        Optional<WorldPoint> first = nearest(player, trails);
        if (first.isEmpty()) {
            return List.of();
        }
        List<WorldPoint> partners =
                trails.stream()
                        .filter(trail -> !trail.equals(first.get()))
                        .filter(trail -> trail.distanceTo2D(first.get()) <= MAX_PAIR_DISTANCE)
                        .collect(Collectors.toList());
        Optional<WorldPoint> second = nearest(first.get(), partners);
        if (second.isEmpty()) {
            return List.of(first.get());
        }
        return List.of(first.get(), second.get());
    }

    private static Optional<WorldPoint> nearest(
            final WorldPoint origin, final List<WorldPoint> points) {
        return points.stream().min(Comparator.comparingInt(point -> point.distanceTo2D(origin)));
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
