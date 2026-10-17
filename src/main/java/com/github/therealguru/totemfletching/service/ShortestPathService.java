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
 * flowers, as long as they lie within the detour limit of the path to the next totem. That path is
 * asked from Shortest Path with a {@code query} message, so it is the real walking route. When
 * Shortest Path does not answer, for example an older version, the straight line to the totem is
 * used instead. Shortest Path shows one path at a time, so the route is sent one stop at a time:
 * the next stop is sent when the current one is reached and its flower stops being inactive.
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
    private static final String QUERY = "query";
    private static final String RESULT = "result";
    private static final String QUERY_ID_PREFIX = "totem-fletching-";
    private static final int MAX_SITE_DRIFT = 3;
    private static final int MAX_PAIR_DISTANCE = 12;
    private static final int QUERY_TIMEOUT_TICKS = 5;
    private static final int QUERY_BACKOFF_TICKS = 100;

    private final EventBus eventBus;
    private final TotemFletchingConfig config;
    private final TotemService totemService;
    private final EntTrailService entTrailService;

    private WorldPoint sentTarget;
    private boolean wasInside;
    private Set<WorldPoint> trailSnapshot = new HashSet<>();
    private int tick;
    private int queriesBlockedUntil;
    private int lastQueryId;
    private PendingQuery pending;
    private WorldPoint pathTarget;
    private List<WorldPoint> pathTiles = List.of();

    /** A path query that Shortest Path has not answered yet. */
    private static final class PendingQuery {
        private final String id;
        private final WorldPoint next;
        private final List<WorldPoint> flowers;
        private final int startedTick;

        private PendingQuery(
                final String id,
                final WorldPoint next,
                final List<WorldPoint> flowers,
                final int startedTick) {
            this.id = id;
            this.next = next;
            this.flowers = flowers;
            this.startedTick = startedTick;
        }
    }

    /**
     * Sends a route to the next stop, or clears the route when there is none or the player is
     * outside Auburnvale. With ent trails in play the real path is asked from Shortest Path first,
     * and the route is sent when the answer arrives. Does nothing if the setting is off or the next
     * stop has not changed since the last route was sent.
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
        Optional<WorldPoint> totem = selectTarget(player, totemService.getTotems());
        if (totem.isEmpty()) {
            clear();
            return;
        }
        WorldPoint next = totem.get();
        List<WorldPoint> flowers = config.routeViaEntTrails() ? trails : List.of();
        if (flowers.isEmpty()) {
            pending = null;
            send(player, next);
            return;
        }
        if (isWaitingFor(next, flowers)) {
            return;
        }
        List<WorldPoint> known = knownPath(player, next);
        if (!known.isEmpty()) {
            sendRoute(player, next, flowers, known);
        } else if (tick < queriesBlockedUntil) {
            sendRoute(player, next, flowers, straightLine(player, next));
        } else {
            askForPath(player, next, flowers);
        }
    }

    private boolean isWaitingFor(final WorldPoint next, final List<WorldPoint> flowers) {
        return pending != null && pending.next.equals(next) && pending.flowers.equals(flowers);
    }

    private List<WorldPoint> knownPath(final WorldPoint player, final WorldPoint next) {
        if (!next.equals(pathTarget) || pathTiles.isEmpty()) {
            return List.of();
        }
        if (distanceToTiles(player, pathTiles) > config.trailDetourLimit()) {
            return List.of();
        }
        return pathTiles;
    }

    private void askForPath(
            final WorldPoint player, final WorldPoint next, final List<WorldPoint> flowers) {
        lastQueryId++;
        String id = QUERY_ID_PREFIX + lastQueryId;
        pending = new PendingQuery(id, next, flowers, tick);
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("start", player);
        data.put("target", next);
        eventBus.post(new PluginMessage(NAMESPACE, QUERY, data));
    }

    /**
     * Handles the answer to a path query. The route is then built from the real path. An answer
     * without a path falls back to the straight line. Answers to other queries are ignored.
     *
     * @param message any plugin message
     * @param player the player's current tile, or null if not yet known
     */
    public void onPluginMessage(final PluginMessage message, final WorldPoint player) {
        if (!NAMESPACE.equals(message.getNamespace()) || !RESULT.equals(message.getName())) {
            return;
        }
        Map<String, Object> data = message.getData();
        PendingQuery query = pending;
        if (query == null || data == null || !query.id.equals(data.get("id"))) {
            return;
        }
        pending = null;
        if (!config.useShortestPath() || player == null) {
            return;
        }
        List<WorldPoint> tiles = tilesOf(data.get("path"));
        if (tiles.isEmpty()) {
            log.debug(
                    "Shortest Path found no path for query {}, using the straight line", query.id);
            sendRoute(player, query.next, query.flowers, straightLine(player, query.next));
            return;
        }
        log.debug("Using the real path of {} tiles from query {}", tiles.size(), query.id);
        pathTarget = query.next;
        pathTiles = tiles;
        queriesBlockedUntil = 0;
        sendRoute(player, query.next, query.flowers, tiles);
    }

    private void sendRoute(
            final WorldPoint player,
            final WorldPoint next,
            final List<WorldPoint> flowers,
            final List<WorldPoint> path) {
        List<WorldPoint> route = buildRoute(player, next, flowers, config.trailDetourLimit(), path);
        send(player, route.get(0));
    }

    private void send(final WorldPoint player, final WorldPoint target) {
        if (target.equals(sentTarget)) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("start", player);
        data.put("target", target);
        eventBus.post(new PluginMessage(NAMESPACE, PATH, data));
        sentTarget = target;
    }

    /** Removes the route if this service sent one, and drops any query still waiting. */
    public void clear() {
        pending = null;
        pathTarget = null;
        pathTiles = List.of();
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
        queriesBlockedUntil = 0;
    }

    /**
     * Sends or clears the route when the player walks into or out of Auburnvale, and sends the next
     * stop when the set of inactive ent trails changes. Also gives up on a path query that Shortest
     * Path has not answered in time, and uses the straight line instead. Sends nothing otherwise.
     *
     * @param player the player's current tile, or null if not yet known
     */
    public void onGameTick(final WorldPoint player) {
        onGameTick(player, currentTrails());
    }

    void onGameTick(final WorldPoint player, final List<WorldPoint> trails) {
        tick++;
        if (player == null) {
            return;
        }
        giveUpOnLateQuery(player);
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

    private void giveUpOnLateQuery(final WorldPoint player) {
        PendingQuery query = pending;
        if (query == null || tick - query.startedTick < QUERY_TIMEOUT_TICKS) {
            return;
        }
        log.debug(
                "Shortest Path did not answer query {} in time, using the straight line", query.id);
        pending = null;
        queriesBlockedUntil = tick + QUERY_BACKOFF_TICKS;
        if (config.useShortestPath()) {
            sendRoute(player, query.next, query.flowers, straightLine(player, query.next));
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

    private static List<WorldPoint> tilesOf(final Object value) {
        List<WorldPoint> tiles = new ArrayList<>();
        if (value instanceof List<?>) {
            for (Object element : (List<?>) value) {
                if (element instanceof WorldPoint) {
                    tiles.add((WorldPoint) element);
                }
            }
        }
        return tiles;
    }

    /**
     * Builds the stops in order using the straight line to the next totem as the path: the nearest
     * pair of inactive ent trail flowers, if any, then the nearest totem that is not fully
     * decorated.
     *
     * @param player the player's current tile
     * @param totems all totems
     * @param trails the tiles of the inactive ent trail flowers
     * @param maxDetour how far a flower may be from the path to the next totem, in tiles
     * @return the stops in order, or empty if no totem needs work
     */
    static List<WorldPoint> selectRoute(
            final WorldPoint player,
            final List<Totem> totems,
            final List<WorldPoint> trails,
            final int maxDetour) {
        Optional<WorldPoint> totem = selectTarget(player, totems);
        if (totem.isEmpty()) {
            return List.of();
        }
        WorldPoint next = totem.get();
        return buildRoute(player, next, trails, maxDetour, straightLine(player, next));
    }

    /**
     * Builds the stops in order for a known next totem and path to it: the nearest pair of flowers
     * within {@code maxDetour} tiles of the path, if any, then the totem.
     *
     * @param player the player's current tile
     * @param next the tile of the next totem
     * @param trails the tiles of the inactive ent trail flowers
     * @param maxDetour how far a flower may be from the path, in tiles
     * @param path the tiles of the path from the player to the next totem
     * @return the stops in order, ending with the totem
     */
    static List<WorldPoint> buildRoute(
            final WorldPoint player,
            final WorldPoint next,
            final List<WorldPoint> trails,
            final int maxDetour,
            final List<WorldPoint> path) {
        List<WorldPoint> besidePath =
                trails.stream()
                        .filter(trail -> distanceToTiles(trail, path) <= maxDetour)
                        .collect(Collectors.toList());
        List<WorldPoint> route = new ArrayList<>(selectTrailPair(player, besidePath));
        route.add(next);
        return route;
    }

    /**
     * Lists the tiles on the straight line between two tiles, both ends included.
     *
     * @param from the first tile
     * @param to the last tile
     * @return the tiles in order
     */
    static List<WorldPoint> straightLine(final WorldPoint from, final WorldPoint to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        List<WorldPoint> tiles = new ArrayList<>(steps + 1);
        for (int step = 0; step <= steps; step++) {
            int x = steps == 0 ? from.getX() : from.getX() + Math.round((float) dx * step / steps);
            int y = steps == 0 ? from.getY() : from.getY() + Math.round((float) dy * step / steps);
            tiles.add(new WorldPoint(x, y, from.getPlane()));
        }
        return tiles;
    }

    private static double distanceToTiles(final WorldPoint point, final List<WorldPoint> tiles) {
        double nearest = Double.MAX_VALUE;
        for (WorldPoint tile : tiles) {
            nearest =
                    Math.min(
                            nearest,
                            Math.hypot(point.getX() - tile.getX(), point.getY() - tile.getY()));
        }
        return nearest;
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
