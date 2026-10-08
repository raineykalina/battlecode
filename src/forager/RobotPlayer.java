package forager;

import battlecode.common.*;

import java.util.Random;

/**
 * ============================================================================ ARCHETYPE 1 — "THE
 * FORAGER" (Economy / Cheese-first)
 * ============================================================================
 *
 * <p>BIG IDEA -------- Points in Uneasy Alliances come from three things (see spec Eq. 1 & 2): -
 * damage dealt to cats - number of living rat kings - CHEESE transferred by baby rats to the rat
 * king <-- this bot's focus
 *
 * <p>The Forager treats cheese as king. Baby rats scan for cheese, grab it, and sprint it back to
 * the rat king to convert it into GLOBAL cheese (which both scores points AND pays for everything
 * else: spawning, traps, king upkeep). It stays cooperative and rarely picks fights.
 *
 * <p>WHY IT CAN WIN -------------- A well-fed king can out-spawn opponents, and the 0.2 cheese term
 * is a reliable, low-risk source of points that opponents often ignore.
 *
 * <p>WHY IT CAN LOSE --------------- It does little cat damage (0.5 / 0.3 weight!) and won't defend
 * well if backstabbed. See the "TOURNAMENT IDEAS" comments to toughen it up.
 *
 * <p>--------------------------------------------------------------------------- READING THIS FILE
 * (for students) --------------------------------------------------------------------------- run()
 * -> the loop every robot runs, once per turn, forever. runKing() -> what the 3x3 rat king does.
 * runBabyRat() -> what each baby rat does. Helpers at the bottom handle turning (vision cones!),
 * moving, and sensing.
 *
 * <p>⚠ API NOTE: a few method names below are marked standard Battlecode naming but should be
 * checked against the official 2026 javadoc / examplefuncsplayer before the event. See README for
 * the full list. ============================================================================
 */
public strictfp class RobotPlayer {

    // Deterministic-per-robot randomness. Seeding with the robot ID means the
    // match still replays identically (important for Battlecode debugging).
    static Random rng;

    // Cache the enemy team once; team never changes during a game.
    static Team enemy;

    // 🔧 CUSTOMIZE: how much cheese a baby rat will carry before heading home.
    // Remember: carrying cheese SLOWS you down (cooldown x 0.01 * amount).
    // Lower = more trips but faster rats; higher = fewer trips but sluggish.
    static final int CHEESE_HAUL_TARGET = 30;

    public static void run(RobotController rc) {
        rng = new Random(rc.getID());
        enemy = rc.getTeam().opponent();

        while (true) {
            try {
                if (isKing(rc)) {
                    runKing(rc);
                } else {
                    runBabyRat(rc);
                }
            } catch (GameActionException e) {
                // Thrown when we attempt something illegal (e.g. move onto a
                // wall). We guard with can*() checks, but the game state can
                // shift mid-turn, so we swallow these quietly.
            } catch (Exception e) {
                // Any other bug: don't let one bad turn kill the robot.
            } finally {
                // ALWAYS end the turn here. Forgetting Clock.yield() is the #1
                // beginner bug — the robot runs out of bytecode mid-thought.
                Clock.yield();
            }
        }
    }

    static MapLocation lastLoc = null;
    static int stuckCounter = 0;
    static MapLocation avoidTarget = null; // cheese we gave up on

    static boolean isStuck(RobotController rc) {
        MapLocation here = rc.getLocation();
        if (here.equals(lastLoc)) stuckCounter++;
        else stuckCounter = 0;

        lastLoc = here;
        return stuckCounter >= 4; // true if no movement for 4 turns
    }

    static void drawDebug(RobotController rc, String state, MapLocation target)
            throws GameActionException {
        rc.setIndicatorString(
                state + " raw=" + rc.getRawCheese() + (target != null ? " tgt=" + target : ""));
        if (rc.getID() % 20 == 0) { // sample ~1 in 20 rats to limit spam
            System.out.println(
                    rc.getID()
                            + " r"
                            + rc.getRoundNum()
                            + " "
                            + state
                            + " raw="
                            + rc.getRawCheese()
                            + " loc="
                            + rc.getLocation()
                            + " tgt="
                            + target);
        }
        if (target != null) {
            int r = 0, g = 0, b = 0;
            switch (state) {
                case "DELIVER":
                    b = 255;
                    break; // blue  = heading to king
                case "COLLECT":
                    g = 255;
                    break; // green = heading to cheese
                case "EXPLORE":
                    r = 255;
                    g = 255;
                    break; // yellow = wandering
            }
            rc.setIndicatorLine(rc.getLocation(), target, r, g, b);
        }
    }

    // ------------------------------------------------------------------ KING
    private static void runKing(RobotController rc) throws GameActionException {
        MapLocation here = rc.getLocation();

        // 1) Eat any cheese we're standing on (king cheese -> global instantly).
        tryKingCollectCheese(rc);

        // 2) Spawn foragers as long as we can afford it. The Forager wants a
        //    big collection workforce early.
        // 🔧 CUSTOMIZE: change the cheese floor (200) to spawn more/less
        //    aggressively, or gate spawning on rc.getRoundNum() for timing.
        if (getGlobalCheese(rc) > 200) {
            for (MapLocation loc : rc.getAllLocationsWithinRadiusSquared(here, 4)) {
                if (loc.distanceSquaredTo(here) <= 2) continue; // skip the 3x3 body
                if (rc.canBuildRat(loc)) {
                    rc.buildRat(loc);
                    break;
                }
            }
        }

        // 3) Broadcast "collect here" intent via the shared array (kings only
        //    may WRITE; any rat may READ). We publish our own location so lost
        //    foragers know where home is.
        //    Array is 64 ints, each 0..1023 — so pack coordinates carefully.
        try {
            rc.writeSharedArray(0, here.x & 1023);
            rc.writeSharedArray(1, here.y & 1023);
        } catch (GameActionException ignored) {
        }

        // A Forager king mostly sits still (moving costs a 40 cooldown and
        // strands rats trying to deliver). We do NOT chase cats.
        if (rc.getRoundNum() % 10 == 0) { // every 10 rounds
            System.out.println(
                    "KING "
                            + rc.getID()
                            + " r"
                            + rc.getRoundNum()
                            + " hp="
                            + rc.getHealth()
                            + " globalCheese="
                            + rc.getGlobalCheese()
                            + " loc="
                            + rc.getLocation());
        }
    }

    // -------------------------------------------------------------- BABY RAT
    private static void runBabyRat(RobotController rc) throws GameActionException {
        // Scan our surroundings. Baby rats only see a 90° cone in their facing
        // direction (20-unit range), so turning to look around matters!
        int carried = getRawCheese(rc);

        if (carried >= CHEESE_HAUL_TARGET) {
            // -------- DELIVERY MODE: run the cheese home to the king --------
            MapLocation king = findKing(rc);
            if (king == null) king = readKingLocation(rc); // fall back to array
            if (king != null) {
                // The king is a 3x3 whose reported location is its CENTER, so a
                // courier touching the king's body sits within distance^2 8.
                if (rc.getLocation().distanceSquaredTo(king) <= 8) {
                    tryDeliverCheese(rc, king);
                    drawDebug(rc, "DELIVER", king);
                } else {
                    moveToward(rc, king);
                    drawDebug(rc, "DELIVER", king);
                }
            } else {
                // We don't know where home is — wander until we sense the king.
                scanAndWander(rc);
                drawDebug(rc, "EXPLORE", king);
            }
            return;
        }

        // -------- COLLECT MODE: find the nearest cheese and grab it --------
        MapLocation cheese = findNearestCheese(rc);
        if (cheese != null && cheese.equals(avoidTarget)) cheese = null; // ignore blocked cheese
        if (cheese != null) {
            if (rc.getLocation().distanceSquaredTo(cheese) <= 2) {
                tryCollectCheese(rc, cheese);
            } else {
                moveToward(rc, cheese);
                if (isStuck(rc)) {
                    avoidTarget = cheese;
                } // give up on this one
            }
            drawDebug(rc, "COLLECT", cheese);
        } else {
            // No cheese in view. Turn to scan a new cone, then explore.
            scanAndWander(rc);
            drawDebug(rc, "EXPLORE", exploreTarget);
        }

        // 🔧 TOURNAMENT IDEA: if a cat is adjacent, drop everything and RUN, or
        //    place a cat trap to farm the valuable "damage to cats" score.
        //    Add a call to fleeFromCats(rc) here to make Foragers survivable.
    }

    // ============================ HELPER METHODS ============================
    // These are deliberately simple. Students: this is the best place to make
    // a bot smarter — better pathfinding here helps EVERY archetype.

    /** True if this robot is the 3x3 rat king rather than a baby rat. */
    static boolean isKing(RobotController rc) {
        return rc.getType() == UnitType.RAT_KING;
        // (UnitType also has rc.getType().isRatKingType() if you prefer.)
    }

    /**
     * Move one step toward 'target'. Because rats see only a 90° cone, we first TURN to face the
     * target (so we can sense along the way), then step.
     */
    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;

        Direction dir = rc.getLocation().directionTo(target);
        if (dir == null || dir == Direction.CENTER) return;

        // Try straight at target first.
        if (rc.canMove(dir)) {
            faceAndMove(rc, dir);
            return;
        }

        // Blocked: rotate (always the same way) until something opens.
        Direction t = dir;
        for (int i = 0; i < 7; i++) {
            t = t.rotateLeft(); // consintent handedness = follows the wall
            if (rc.canMove(t)) {
                faceAndMove(rc, t);
                return;
            }
        }
    }

    static void faceAndMove(RobotController rc, Direction d) throws GameActionException {
        if (rc.canTurn(d)) rc.turn(d);
        if (rc.canMove(d)) rc.move(d);
    }

    static MapLocation exploreTarget = null;

    /** Turn to a fresh direction (to scan a new cone), then take a random step. */
    static void scanAndWander(RobotController rc) throws GameActionException {
        MapLocation here = rc.getLocation();

        // Pick a new far-off target if we have none or we've arrived near it.
        if (exploreTarget == null || here.distanceSquaredTo(exploreTarget) <= 8) {
            int w = rc.getMapWidth(), h = rc.getMapHeight();
            exploreTarget = new MapLocation(rng.nextInt(w), rng.nextInt(h));
        }

        moveToward(rc, exploreTarget);
    }

    /** Nearest sensed tile that has cheese on it, or null. */
    static MapLocation findNearestCheese(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        MapLocation here = rc.getLocation();
        for (MapInfo mi : tiles) {
            if (cheeseAmount(mi) > 0) {
                int d = here.distanceSquaredTo(mi.getMapLocation());
                if (d < bestDist) {
                    bestDist = d;
                    best = mi.getMapLocation();
                }
            }
        }
        return best;
    }

    /** How much cheese sits on a sensed tile (0 if none). */
    static int cheeseAmount(MapInfo mi) {
        return mi.getCheeseAmount();
    }

    /** Find an allied rat king in vision, or null. */
    static MapLocation findKing(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo r : allies) {
            if (r.getType() == UnitType.RAT_KING) return r.getLocation();
        }
        return null;
    }

    /** Read the king location the king published into the shared array. */
    static MapLocation readKingLocation(RobotController rc) {
        try {
            int x = rc.readSharedArray(0);
            int y = rc.readSharedArray(1);
            if (x == 0 && y == 0) return null;
            return new MapLocation(x, y);
        } catch (GameActionException e) {
            return null;
        }
    }

    // ---- Thin wrappers around the exact cheese/collect/deliver API. ----
    // Grouped here so that if a name is wrong you fix it in ONE place.

    static int getGlobalCheese(RobotController rc) {
        try {
            return rc.getGlobalCheese();
        } catch (Throwable t) {
            return 0;
        }
    }

    static int getRawCheese(RobotController rc) {
        try {
            return rc.getRawCheese();
        } catch (Throwable t) {
            return 0;
        }
    }

    static void tryCollectCheese(RobotController rc, MapLocation loc) throws GameActionException {
        // Pick up ALL cheese on the tile. There is also a pickUpCheese(loc, amount)
        // overload if you'd rather leave some behind to stay fast.
        if (rc.canPickUpCheese(loc)) rc.pickUpCheese(loc);
    }

    static void tryKingCollectCheese(RobotController rc) throws GameActionException {
        MapLocation here = rc.getLocation();
        if (rc.canPickUpCheese(here)) rc.pickUpCheese(here);
    }

    static void tryDeliverCheese(RobotController rc, MapLocation king) throws GameActionException {
        // transferCheese(loc, amount) hands raw cheese to an allied king; it
        // becomes GLOBAL cheese (and scores points). Hand over everything we
        // carry. 'king' is the king's center — canTransferCheese confirms reach.
        int amount = rc.getRawCheese();
        if (amount > 0 && rc.canTransferCheese(king, amount)) {
            rc.transferCheese(king, amount);
        }
    }
}