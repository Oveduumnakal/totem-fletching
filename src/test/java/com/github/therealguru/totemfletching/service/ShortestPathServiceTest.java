package com.github.therealguru.totemfletching.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.therealguru.totemfletching.TotemFletchingConfig;
import com.github.therealguru.totemfletching.model.TotemSite;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import org.junit.Before;
import org.junit.Test;

/** Tests target selection and message sending of {@link ShortestPathService}. */
public class ShortestPathServiceTest {

    private static final int DECORATED = 4;
    private static final WorldPoint SITE_8_TILE = TotemSite.SITE_8.getLocation();
    private static final WorldPoint OUTSIDE = new WorldPoint(3200, 3200, 0);
    private static final WorldPoint SITE_7_TILE = TotemSite.SITE_7.getLocation();
    private static final WorldPoint WEST_OF_SITE_7 = new WorldPoint(1435, 3338, 0);
    private static final WorldPoint FLOWER_1 = new WorldPoint(1441, 3342, 0);
    private static final WorldPoint FLOWER_2 = new WorldPoint(1446, 3343, 0);
    private static final WorldPoint FAR_PARTNER = new WorldPoint(1454, 3342, 0);
    private static final WorldPoint OFF_PATH_PARTNER = new WorldPoint(1441, 3353, 0);
    private static final WorldPoint OFF_PATH_FLOWER = new WorldPoint(1441, 3362, 0);
    private static final int DETOUR = 10;

    private final TestConfig config = new TestConfig();
    private final EventBus eventBus = new EventBus();
    private final MessageCollector messages = new MessageCollector();
    private TotemService totemService;
    private ShortestPathService service;

    /** Config whose Shortest Path settings can be switched during a test. */
    public static class TestConfig implements TotemFletchingConfig {
        private boolean shortestPath = true;
        private boolean entTrails = false;
        private int detour = DETOUR;

        @Override
        public boolean useShortestPath() {
            return shortestPath;
        }

        @Override
        public boolean routeViaEntTrails() {
            return entTrails;
        }

        @Override
        public int trailDetourLimit() {
            return detour;
        }
    }

    /** Records every plugin message posted on the event bus. */
    public static class MessageCollector {
        private final List<PluginMessage> received = new ArrayList<>();

        @Subscribe
        public void onPluginMessage(PluginMessage message) {
            received.add(message);
        }
    }

    @Before
    public void setUp() {
        eventBus.register(messages);
        totemService = new TotemService(config, null);
        service = new ShortestPathService(eventBus, config, totemService, new EntTrailService());
    }

    private void decorate(int totemId) {
        totemService.getTotems().get(totemId - 1).setDecoration(DECORATED);
    }

    @Test
    public void selectTargetPicksNearestTotem() {
        Optional<WorldPoint> target =
                ShortestPathService.selectTarget(SITE_8_TILE, totemService.getTotems());

        assertEquals(Optional.of(SITE_8_TILE), target);
    }

    @Test
    public void selectTargetSkipsDecoratedTotems() {
        decorate(8);

        Optional<WorldPoint> target =
                ShortestPathService.selectTarget(SITE_8_TILE, totemService.getTotems());

        assertEquals(Optional.of(TotemSite.SITE_5.getLocation()), target);
    }

    @Test
    public void selectTargetIsEmptyWhenEveryTotemIsDecorated() {
        for (int totemId = 1; totemId <= 8; totemId++) {
            decorate(totemId);
        }

        assertFalse(
                ShortestPathService.selectTarget(SITE_8_TILE, totemService.getTotems())
                        .isPresent());
    }

    @Test
    public void updateSendsStartAndTargetOnce() {
        service.update(SITE_8_TILE);
        service.update(SITE_8_TILE);

        assertEquals(1, messages.received.size());
        PluginMessage message = messages.received.get(0);
        assertEquals("shortestpath", message.getNamespace());
        assertEquals("path", message.getName());
        assertEquals(SITE_8_TILE, message.getData().get("start"));
        assertEquals(SITE_8_TILE, message.getData().get("target"));
    }

    @Test
    public void updateSendsNothingWhenSettingIsOff() {
        config.shortestPath = false;

        service.update(SITE_8_TILE);

        assertTrue(messages.received.isEmpty());
    }

    @Test
    public void updateSendsNewPathWhenTargetChanges() {
        service.update(SITE_8_TILE);
        decorate(8);
        service.update(SITE_8_TILE);

        assertEquals(2, messages.received.size());
        assertEquals(
                TotemSite.SITE_5.getLocation(), messages.received.get(1).getData().get("target"));
    }

    @Test
    public void updateClearsWhenNoTotemNeedsWork() {
        service.update(SITE_8_TILE);
        for (int totemId = 1; totemId <= 8; totemId++) {
            decorate(totemId);
        }
        service.update(SITE_8_TILE);

        assertEquals(2, messages.received.size());
        assertEquals("clear", messages.received.get(1).getName());
    }

    @Test
    public void clearSendsNothingWhenNoRouteWasSent() {
        service.clear();

        assertTrue(messages.received.isEmpty());
    }

    @Test
    public void clearSendsOnceAfterARouteWasSent() {
        service.update(SITE_8_TILE);
        service.clear();
        service.clear();

        assertEquals(2, messages.received.size());
        assertEquals("clear", messages.received.get(1).getName());
    }

    @Test
    public void updateOutsideAuburnvaleSendsNothing() {
        service.update(OUTSIDE);

        assertTrue(messages.received.isEmpty());
    }

    @Test
    public void walkingOutOfAuburnvaleClearsAndWalkingBackSendsAgain() {
        service.onGameTick(SITE_8_TILE);
        service.onGameTick(SITE_8_TILE);
        service.onGameTick(OUTSIDE);
        service.onGameTick(SITE_8_TILE);

        assertEquals(3, messages.received.size());
        assertEquals("path", messages.received.get(0).getName());
        assertEquals("clear", messages.received.get(1).getName());
        assertEquals("path", messages.received.get(2).getName());
    }

    @Test
    public void shutDownClearsAndForgetsSide() {
        service.onGameTick(SITE_8_TILE);
        service.shutDown();
        service.onGameTick(SITE_8_TILE);

        assertEquals(3, messages.received.size());
        assertEquals("clear", messages.received.get(1).getName());
        assertEquals("path", messages.received.get(2).getName());
    }

    private WorldPoint targetOfMessage(int index) {
        return (WorldPoint) messages.received.get(index).getData().get("target");
    }

    private List<WorldPoint> route(List<WorldPoint> trails, int maxDetour) {
        return ShortestPathService.selectRoute(
                WEST_OF_SITE_7, totemService.getTotems(), trails, maxDetour);
    }

    @Test
    public void selectTrailPairIsEmptyWithoutFlowers() {
        assertTrue(ShortestPathService.selectTrailPair(WEST_OF_SITE_7, List.of()).isEmpty());
    }

    @Test
    public void selectTrailPairVisitsNearestFlowerThenItsPartner() {
        List<WorldPoint> pair =
                ShortestPathService.selectTrailPair(WEST_OF_SITE_7, List.of(FLOWER_2, FLOWER_1));

        assertEquals(List.of(FLOWER_1, FLOWER_2), pair);
    }

    @Test
    public void selectTrailPairIgnoresFlowersTooFarToBePartners() {
        List<WorldPoint> pair =
                ShortestPathService.selectTrailPair(WEST_OF_SITE_7, List.of(FLOWER_1, FAR_PARTNER));

        assertEquals(List.of(FLOWER_1), pair);
    }

    @Test
    public void selectTrailPairTreatsFlowersOnTheSameTileAsOne() {
        List<WorldPoint> pair =
                ShortestPathService.selectTrailPair(WEST_OF_SITE_7, List.of(FLOWER_1, FLOWER_1));

        assertEquals(List.of(FLOWER_1), pair);
    }

    @Test
    public void selectRouteWithoutFlowersIsJustTheNearestTotem() {
        assertEquals(List.of(SITE_7_TILE), route(List.of(), DETOUR));
    }

    @Test
    public void selectRouteVisitsTheFlowersThenTheNextTotem() {
        assertEquals(
                List.of(FLOWER_1, FLOWER_2, SITE_7_TILE),
                route(List.of(FLOWER_1, FLOWER_2), DETOUR));
    }

    @Test
    public void selectRouteWithALoneFlowerVisitsItThenTheTotem() {
        assertEquals(List.of(FLOWER_1, SITE_7_TILE), route(List.of(FLOWER_1), DETOUR));
    }

    @Test
    public void selectRouteSkipsFlowersBeyondTheDetourLimit() {
        assertEquals(List.of(SITE_7_TILE), route(List.of(OFF_PATH_FLOWER), DETOUR));
    }

    @Test
    public void selectRouteSkipsFlowersWhenTheLimitIsTighter() {
        assertEquals(List.of(SITE_7_TILE), route(List.of(FLOWER_1, FLOWER_2), 1));
    }

    @Test
    public void selectRouteDropsAPartnerBeyondTheDetourLimit() {
        assertEquals(
                List.of(FLOWER_1, SITE_7_TILE), route(List.of(FLOWER_1, OFF_PATH_PARTNER), DETOUR));
    }

    @Test
    public void selectRouteIsEmptyWhenNoTotemNeedsWorkEvenWithFlowers() {
        for (int totemId = 1; totemId <= 8; totemId++) {
            decorate(totemId);
        }

        assertTrue(route(List.of(FLOWER_1), DETOUR).isEmpty());
    }

    @Test
    public void updateIgnoresFlowersWhenTheEntTrailSettingIsOff() {
        service.update(WEST_OF_SITE_7, List.of(FLOWER_1, FLOWER_2));

        assertEquals(1, messages.received.size());
        assertEquals(SITE_7_TILE, targetOfMessage(0));
    }

    @Test
    public void updateSendsOneStopAtATimeThroughTheFlowers() {
        config.entTrails = true;

        service.update(WEST_OF_SITE_7, List.of(FLOWER_1, FLOWER_2));
        service.update(FLOWER_1, List.of(FLOWER_2));
        service.update(FLOWER_2, List.of());

        assertEquals(3, messages.received.size());
        assertEquals(FLOWER_1, targetOfMessage(0));
        assertEquals(FLOWER_2, targetOfMessage(1));
        assertEquals(SITE_7_TILE, targetOfMessage(2));
    }

    @Test
    public void updateUsesTheConfiguredDetourLimit() {
        config.entTrails = true;
        config.detour = 1;

        service.update(WEST_OF_SITE_7, List.of(FLOWER_1, FLOWER_2));

        assertEquals(1, messages.received.size());
        assertEquals(SITE_7_TILE, targetOfMessage(0));
    }

    @Test
    public void onGameTickSendsTheNextStopOnlyWhenTheFlowersChange() {
        config.entTrails = true;

        service.onGameTick(WEST_OF_SITE_7, List.of(FLOWER_1, FLOWER_2));
        service.onGameTick(WEST_OF_SITE_7, List.of(FLOWER_1, FLOWER_2));
        service.onGameTick(FLOWER_1, List.of(FLOWER_2));
        service.onGameTick(FLOWER_1, List.of(FLOWER_2));
        service.onGameTick(FLOWER_2, List.of());

        assertEquals(3, messages.received.size());
        assertEquals(FLOWER_1, targetOfMessage(0));
        assertEquals(FLOWER_2, targetOfMessage(1));
        assertEquals(SITE_7_TILE, targetOfMessage(2));
    }

    @Test
    public void onGameTickSendsNothingForFlowerChangesWhenTheEntTrailSettingIsOff() {
        service.onGameTick(WEST_OF_SITE_7, List.of());
        service.onGameTick(WEST_OF_SITE_7, List.of(FLOWER_1, FLOWER_2));

        assertEquals(1, messages.received.size());
        assertEquals(SITE_7_TILE, targetOfMessage(0));
    }
}
