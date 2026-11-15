package io.github.forest611.mcclientmcp.input;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Movement input injected in place of the keyboard. The values are written into the player's input object right after
 * the game reads the real keys, so movement takes the same path as a human pressing them.
 */
public final class ScriptedInput {
    private static boolean sneak;
    private static boolean jump;
    private static boolean sprint;
    private static float forward;
    private static float strafe;
    private static boolean moving;
    private static boolean holdingAttack;

    private ScriptedInput() {
    }

    /** Sneak is a held state: it stays until changed. */
    public static void setSneak(boolean value) {
        sneak = value;
    }

    public static boolean isSneaking() {
        return sneak;
    }

    public static void setJump(boolean value) {
        jump = value;
    }

    /**
     * @param forwardImpulse -1..1, positive is forward
     * @param strafeImpulse  -1..1, positive is left
     */
    public static void setMove(float forwardImpulse, float strafeImpulse, boolean sprinting) {
        forward = forwardImpulse;
        strafe = strafeImpulse;
        sprint = sprinting;
        moving = true;
    }

    public static void stopMove() {
        forward = 0;
        strafe = 0;
        sprint = false;
        moving = false;
    }

    /**
     * While a scripted block break is in progress the vanilla per-tick "attack key is up" handling must not run,
     * or it would abort the break every tick.
     */
    public static void setHoldingAttack(boolean value) {
        holdingAttack = value;
    }

    public static boolean isHoldingAttack() {
        return holdingAttack;
    }

    public static void reset() {
        stopMove();
        jump = false;
        sneak = false;
        holdingAttack = false;
    }

    public static boolean isActive() {
        return sneak || jump || moving;
    }

    public static Input keyPresses(Input real) {
        return new Input(
                forward > 0 || (!moving && real.forward()),
                forward < 0 || (!moving && real.backward()),
                strafe > 0 || (!moving && real.left()),
                strafe < 0 || (!moving && real.right()),
                jump || real.jump(),
                sneak || real.shift(),
                sprint || real.sprint());
    }

    public static Vec2 moveVector(Vec2 real) {
        return moving ? new Vec2(strafe, forward) : real;
    }
}
