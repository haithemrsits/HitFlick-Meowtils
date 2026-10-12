import java.lang.reflect.Field;
import java.util.Arrays;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

// ---------------------------------------------------------------------------
// !! VERIFY THESE IMPORTS !!
// The Meowtils docs only give the packages for SlotProvider / IconProvider.
// The packages below are best guesses -- fix them against your Meowtils jar
// (open it with any decompiler and look for Config, EventTarget, ClientTickEvent).
// ---------------------------------------------------------------------------
import wtf.tatp.meowtils.extension.Extension;
import wtf.tatp.meowtils.config.Config;
import wtf.tatp.meowtils.event.api.EventTarget;
import wtf.tatp.meowtils.event.ClientTickEvent;
import wtf.tatp.meowtils.util.TeamUtil;

/**
 * HitFlick -- silent knockback-direction displacement.
 *
 * ===========================================================================
 *  HOW THIS WORKS WITHOUT MIXINS (read this first)
 * ===========================================================================
 *
 * 1) The server never sees your camera. It only knows the yaw you LAST TOLD IT
 *    about, via C03/C04/C05/C06 flying packets. The C02PacketUseEntity (attack)
 *    packet carries NO rotation. When the server processes an attack it reads
 *    the attacker's server-side rotationYaw, i.e. whatever the most recent
 *    look packet said.
 *
 * 2) So we don't need to touch the client camera at all. We only need the
 *    packet stream to look like this on the wire:
 *
 *        C05Look(realYaw + offset)   <- our spoof   (sent in tick PRE)
 *        C02UseEntity(ATTACK)        <- the VANILLA attack, untouched
 *        C03/C04 movement packet     <- vanilla per-tick packet
 *        C05Look(realYaw)            <- our restore (sent in tick POST)
 *
 *    mc.thePlayer.rotationYaw is never modified, so the screen never moves.
 *    The only desync is on the network, for the duration of one client tick.
 *
 * 3) WHY WE BRACKET THE VANILLA ATTACK INSTEAD OF SENDING OUR OWN C02:
 *    Without Mixins we cannot cancel Minecraft#clickMouse(). If we also sent a
 *    manual C02 the server would get TWO attacks for one click (a double-hit /
 *    reach-and-timing flag, and the second one is wasted on invulnerability
 *    frames anyway). Wrapping the vanilla packet is cleaner: the spoof goes out
 *    BEFORE the click is processed (ClientTickEvent PRE) and the restore goes
 *    out AFTER (ClientTickEvent POST). Exactly one C02 is ever sent.
 *
 * 4) WHY WE NEED TO PREDICT THE CLICK:
 *    In 1.8.9 the mouse queue is drained inside Minecraft#runTick(), AFTER the
 *    PRE tick event fires, so KeyBinding#pressTime is not populated yet at PRE.
 *    Instead we detect the click's rising edge by polling LWJGL directly
 *    (Mouse.isButtonDown / Keyboard.isKeyDown). LWJGL's polled button state is
 *    updated at the end of the previous frame, so at PRE it already shows the
 *    press that runTick is about to turn into an attack.
 *    (Limitation: a click that is pressed AND released inside one frame will
 *    be missed. A human click is ~50-100 ms so this is fine.)
 *
 * 5) REFLECTION:
 *    The core logic uses only PUBLIC 1.8.9 API (addToSendQueue, objectMouseOver,
 *    the C05 constructor), so it needs no reflection. A small reflective
 *    rotationYaw accessor (getYawReflective / setYawReflective) is included
 *    for FUTURE use only; nothing calls it by default. It tries the MCP name
 *    first (Lunar) and the SRG name second (plain Forge).
 *    WARNING: writing the real yaw client-side moves the camera, which is
 *    exactly what this module avoids.
 *
 * 6) TEAM / BOT FILTER:
 *    Players that TeamUtil flags as bots (AntiBot) or teammates are never
 *    flicked; the click goes out as a normal, unspoofed attack.
 *
 * ===========================================================================
 *  PHYSICS CAVEAT (important -- this decides how strong the effect is)
 * ===========================================================================
 *  In vanilla 1.8.9 the BASE knockback direction is computed from POSITIONS
 *  (attacker.pos - target.pos), NOT from yaw. Yaw only enters through the
 *  EXTRA knockback of EntityPlayer#attackTargetEntityWithCurrentItem:
 *      target.addVelocity(-sin(yaw) * kbLevel * 0.5, 0.1, cos(yaw) * kbLevel * 0.5)
 *  where kbLevel = Knockback enchant level + 1 if you are sprinting.
 *  So a flick only bends the sprint/enchant component of the hit. That is why
 *  "onlySprinting" defaults to true: a non-sprint hit has nothing to redirect.
 *
 * ===========================================================================
 *  RISK NOTE
 * ===========================================================================
 *  Two extra rotation packets per hit, an off-tick rotation delta and a
 *  look-then-snap-back pattern are all things server anticheats look for.
 *  Expect this to be detectable on protected servers. Use at your own risk.
 */
public class HitFlick extends Extension {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // ---------------------------------------------------------------------
    // Config values (public + @Config so Meowtils can persist/bind them)
    // ---------------------------------------------------------------------
    @Config public boolean enabled = false;          // module on/off (default off)
    @Config public int key = 0;                      // keybind, 0 = unbound

    @Config public int yawOffset = 60;               // flick size in degrees (45-90)
    @Config public int cooldown = 300;               // ms between flicks
    @Config public boolean onlyOnBridges = false;    // only flick when the flick side has void under it
    @Config public boolean onlySprinting = true;     // only flick on sprint hits (see physics caveat)
    @Config public String side = "Auto";             // Auto / Left / Right

    // ---------------------------------------------------------------------
    // Runtime state (not saved)
    // ---------------------------------------------------------------------
    /** True between our PRE spoof and our POST restore. */
    private boolean flickPending = false;
    /** Attack-button state last tick, used for rising-edge detection. */
    private boolean prevAttackDown = false;
    private long lastFlickMs = 0L;
    private boolean autoFlip = false;
    private static Field lastYawField;
    private static boolean lastYawResolved = false;

    public HitFlick() {
        super("HitFlick", null); // name, author (null hides the author tag)

        tooltip("Silently spoofs your server-side yaw for one tick while you hit, "
              + "bending sprint knockback sideways without moving your camera.");

        slider("Yaw offset", 45, 90, 1, "\u00b0", "yawOffset", int.class);
        slider("Cooldown", 0, 1000, 50, "ms", "cooldown", int.class);
        mode("Side", Arrays.asList("Auto", "Left", "Right"), "side");
        check("Only on bridges", "onlyOnBridges");
        check("Only when sprinting", "onlySprinting");
    }

    // =====================================================================
    //  Lifecycle
    // =====================================================================

    @Override
    public void onDisable() {
        // If we are turned off between PRE and POST, don't leave the server
        // believing we are still looking sideways.
        restoreServerYaw();
    }

    @Override
    public void onReset() {
        // World change: the old connection state is gone, just clear flags.
        flickPending = false;
        prevAttackDown = false;
    }

    // =====================================================================
    //  Main logic
    // =====================================================================

    @EventTarget
    public void onClientTick(ClientTickEvent event) {
        if (!enabled) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.getNetHandler() == null) {
            flickPending = false;
            return;
        }

        if (event.getPhase() == ClientTickEvent.Phase.PRE) {
            handlePre();
        } else {
            handlePost();
        }
    }

    /**
     * PRE: runs BEFORE Minecraft#runTick() drains the mouse queue and fires
     * clickMouse(). If a left click is about to happen on an entity, put the
     * spoofed yaw on the wire first, so it is the "latest" server-side
     * rotation when the vanilla C02 arrives right after it.
     */
    private void handlePre() {
        boolean down = isAttackDown();
        boolean risingEdge = down && !prevAttackDown;
        prevAttackDown = down;

        if (!risingEdge) return;
        if (mc.currentScreen != null) return;          // vanilla ignores world clicks in GUIs

        // mc.objectMouseOver is what clickMouse() will use this tick.
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null
                || mop.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY
                || !(mop.entityHit instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase target = (EntityLivingBase) mop.entityHit;

        // Never flick teammates or AntiCheat bots. TeamUtil works on players
        // only; mobs/other living entities fall through.
        if (target instanceof EntityPlayer) {
            EntityPlayer targetPlayer = (EntityPlayer) target;
            if (TeamUtil.isBot(targetPlayer) || TeamUtil.isTeam(targetPlayer)) return;
        }

        if (onlySprinting && !mc.thePlayer.isSprinting()) return;

        float realYaw = mc.thePlayer.rotationYaw;
        float sign = chooseSide(target, realYaw);
        if (sign == 0f) return;

        long now = System.currentTimeMillis();
        if (now - lastFlickMs < cooldown) return;

        float spoofYaw = flickYawFor(target, realYaw, sign);

        // The ONE extra packet: puts the spoofed yaw on the server before the vanilla C02.
        mc.getNetHandler().addToSendQueue(new net.minecraft.network.play.client.C03PacketPlayer.C05PacketPlayerLook(spoofYaw, mc.thePlayer.rotationPitch, mc.thePlayer.onGround));
        lastFlickMs = now;

        // Tell vanilla "the server's last yaw was spoofYaw". 
        flickPending = !setLastReportedYaw(spoofYaw);
        // The vanilla attack (swing animation + C02) now happens naturally,
        // so the swing is visible client-side and we never send a second C02.
    }

    /**
     * POST: the vanilla click and the vanilla movement packet have been sent.
     * Now tell the server our real yaw again. Vanilla would NOT do this for
     * us: it only sends a look packet when the REAL yaw changed since its own
     * last report, and as far as vanilla knows the server already has it.
     */
    private void handlePost() {
        restoreServerYaw();
    }

    private void restoreServerYaw() {
        if (!flickPending) return;
        flickPending = false;
        if (mc.thePlayer == null || mc.getNetHandler() == null) return;

        mc.getNetHandler().addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(
                mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, mc.thePlayer.onGround));
    }

    // =====================================================================
    //  Side selection / bridge detection
    // =====================================================================

    private static final float MAX_AIM_ERR = 30f;   // max bearing correction (degrees)

    private static float wrap180(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    /** Minecraft yaw pointing from you to the target (direction = -sin, cos). */
    private float bearingTo(net.minecraft.entity.EntityLivingBase t) {
        double dx = t.posX - mc.thePlayer.posX, dz = t.posZ - mc.thePlayer.posZ;
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /**
     * The yaw to spoof for a given side (-1 left, +1 right). Measured from the
     * target's bearing, so both sides give the same knockback magnitude.
     */
    private float flickYawFor(net.minecraft.entity.EntityLivingBase t, float realYaw, float sign) {
        double dx = t.posX - mc.thePlayer.posX, dz = t.posZ - mc.thePlayer.posZ;
        float aimErr = 0f;
        if (dx * dx + dz * dz > 0.36) {   // closer than 0.6 blocks: bearing is noise, ignore it
            aimErr = wrap180(bearingTo(t) - realYaw);
            aimErr = Math.max(-MAX_AIM_ERR, Math.min(MAX_AIM_ERR, aimErr));
        }
        // Snap the TOTAL delta to the sensitivity GCD (one rounding, symmetric)
        return realYaw + snapToGcd(aimErr + sign * yawOffset);
    }

    private float chooseSide(net.minecraft.entity.EntityLivingBase target, float realYaw) {
        if (onlyOnBridges && !target.onGround) return 0f;

        boolean rightVoid = sideHasVoid(target, flickYawFor(target, realYaw, 1f));
        boolean leftVoid  = sideHasVoid(target, flickYawFor(target, realYaw, -1f));

        String mode = (side == null) ? "Auto" : side.trim();
        if (mode.equalsIgnoreCase("Left"))  return (onlyOnBridges && !leftVoid)  ? 0f : -1f;
        if (mode.equalsIgnoreCase("Right")) return (onlyOnBridges && !rightVoid) ? 0f :  1f;

        if (rightVoid && !leftVoid) return  1f;
        if (leftVoid && !rightVoid) return -1f;
        if (rightVoid || !onlyOnBridges) { autoFlip = !autoFlip; return autoFlip ? 1f : -1f; }
        return 0f;
    }

    private boolean sideHasVoid(net.minecraft.entity.EntityLivingBase target, float flickYaw) {
        // Sprint bonus: 0.5 along the spoofed yaw
        double fr = Math.toRadians(flickYaw);
        double bonusX = -Math.sin(fr) * 0.5, bonusZ = Math.cos(fr) * 0.5;

        // Base knockback: 0.4 away from you. When you're very close (e.g. standing on a
        // block above them) the position vector is unstable, so blend toward your look direction.
        double dx = target.posX - mc.thePlayer.posX, dz = target.posZ - mc.thePlayer.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double px = dist > 1.0E-4 ? dx / dist : 0, pz = dist > 1.0E-4 ? dz / dist : 0;
        double lr = Math.toRadians(mc.thePlayer.rotationYaw);
        double lx = -Math.sin(lr), lz = Math.cos(lr);
        double w = Math.min(dist / 1.0, 1.0);          // 1.0 = trust positions, 0 = trust look
        double bx = px * w + lx * (1.0 - w), bz = pz * w + lz * (1.0 - w);
        double bl = Math.sqrt(bx * bx + bz * bz);
        if (bl > 1.0E-4) { bx = bx / bl * 0.4; bz = bz / bl * 0.4; } else { bx = 0; bz = 0; }

        double mx = bx + bonusX, mz = bz + bonusZ;
        double len = Math.sqrt(mx * mx + mz * mz);
        if (len < 1.0E-4) return false;
        mx /= len; mz /= len;

        int voidSamples = 0;
        for (double d : new double[] { 1.0, 1.5, 2.0 }) {
            if (columnIsVoid(target.posX + mx * d, target.posY, target.posZ + mz * d)) voidSamples++;
        }
        return voidSamples >= 2;
    }

    /** True if the 5 blocks below the target's feet level are all air. */
    private boolean columnIsVoid(double x, double y, double z) {
        int bx = net.minecraft.util.MathHelper.floor_double(x);
        int by = net.minecraft.util.MathHelper.floor_double(y);
        int bz = net.minecraft.util.MathHelper.floor_double(z);
        for (int dy = 1; dy <= 5; dy++) {
            if (!mc.theWorld.isAirBlock(new net.minecraft.util.BlockPos(bx, by - dy, bz))) return false;
        }
        return true;
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    // ---------------------------------------------------------------------
    //  Reflection helper: Entity#rotationYaw (unused by default)
    // ---------------------------------------------------------------------

    /** Cached Field for Entity#rotationYaw; null until first resolved. */
    private static Field yawField;

    /**
     * Resolve Entity#rotationYaw once. MCP name first (Lunar Client ships MCP
     * names), SRG name (field_70177_z) as a fallback for plain Forge.
     */
    private static Field resolveYawField() {
        if (yawField != null) return yawField;
        for (String name : new String[] { "rotationYaw", "field_70177_z" }) {
            try {
                Field f = Entity.class.getDeclaredField(name);
                f.setAccessible(true); // fine for MC classes; only java.* is locked on Java 17
                yawField = f;
                return f;
            } catch (NoSuchFieldException ignored) {
                // try the next mapping
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** Read an entity's yaw reflectively; falls back to the direct field on failure. */
    @SuppressWarnings("unused")
    private static float getYawReflective(Entity entity) {
        try {
            Field f = resolveYawField();
            if (f != null) return f.getFloat(entity);
        } catch (Throwable ignored) { }
        return entity.rotationYaw;
    }

    /**
     * Force an entity's REAL yaw client-side. This visibly moves the camera
     * for the local player, so it is not used by the flick logic.
     */
    @SuppressWarnings("unused")
    private static void setYawReflective(Entity entity, float yaw) {
        try {
            Field f = resolveYawField();
            if (f != null) { f.setFloat(entity, yaw); return; }
        } catch (Throwable ignored) { }
        entity.rotationYaw = yaw;
    }

    /**
     * Rising-edge source: is the bound attack key/button physically down?
     * Mouse binds in 1.8.9 are stored as (button - 100), keyboard binds as
     * a positive LWJGL key code.
     */
    private boolean isAttackDown() {
        int code = mc.gameSettings.keyBindAttack.getKeyCode();
        if (code < 0) return Mouse.isButtonDown(code + 100);
        return Keyboard.isKeyDown(code);
    }

    /**
     * Snap an angle to the client's mouse-sensitivity GCD. Real mouse input
     * produces rotation deltas that are multiples of this step; an arbitrary
     * "60.0" would stand out against that pattern. Same formula vanilla uses
     * in EntityRenderer#updateCameraAndRender.
     */
    private float snapToGcd(float degrees) {
        float f = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float gcd = f * f * f * 8.0F * 0.15F;
        if (gcd <= 0f) return degrees;
        return Math.round(degrees / gcd) * gcd;
    }

    private static boolean setLastReportedYaw(float yaw) {
        try {
            if (!lastYawResolved) {
                lastYawResolved = true;
                for (String n : new String[] { "lastReportedYaw", "field_175164_bL" }) {
                    try {
                        Field f = EntityPlayerSP.class.getDeclaredField(n);
                        f.setAccessible(true);
                        lastYawField = f;
                        break;
                    } catch (NoSuchFieldException ignored) { }
                }
            }
            if (lastYawField == null) return false;
            lastYawField.setFloat(mc.thePlayer, yaw);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
