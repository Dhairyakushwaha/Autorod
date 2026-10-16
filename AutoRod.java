package com.example.autorod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = "autorod", name = "AutoRod", version = "1.0", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class AutoRod {

    // ---- Configurable ----
    public static double minDistance   = 6.0;  // blocks, trigger at or beyond this
    public static double maxDistance   = 12.0; // upper bound for the aim ray
    public static int    cooldownTicks = 20;   // delay after a sequence finishes
    public static int    holdTicks     = 3;    // ticks between cast and reel-in
    public static int    timeoutTicks  = 40;   // hard cap on a whole sequence

    private enum State { IDLE, CAST, WAIT, REEL }

    private final Minecraft mc = Minecraft.getMinecraft();
    private KeyBinding toggleKey;

    private boolean enabled = false;
    private State state = State.IDLE;
    private int prevSlot = -1;
    private int rodSlot = -1;
    private int cooldown = 0;
    private int holdTimer = 0;
    private int sequenceTicks = 0;

    @EventHandler
    public void init(FMLInitializationEvent e) {
        toggleKey = new KeyBinding("Toggle AutoRod", Keyboard.KEY_Z, "AutoRod");
        ClientRegistry.registerKeyBinding(toggleKey);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;

        if (mc.thePlayer == null || mc.theWorld == null) {
            resetState();
            return;
        }

        // isPressed() returns true once per fresh press, so holding Z won't re-toggle
        while (toggleKey.isPressed()) {
            enabled = !enabled;
            if (!enabled) abort();
            mc.thePlayer.addChatMessage(new ChatComponentText(
                    "AutoRod: " + (enabled ? "ON" : "OFF")));
        }
        if (!enabled) return;

        if (mc.currentScreen != null) {
            if (state != State.IDLE) abort();
            return;
        }

        if (cooldown > 0) cooldown--;

        if (state != State.IDLE && ++sequenceTicks > timeoutTicks) {
            finish();
            return;
        }

        EntityPlayer self = mc.thePlayer;

        switch (state) {
            case IDLE:
                if (cooldown > 0 || self.fishEntity != null) return;
                if (findTarget() == null) return;
                int slot = findRodSlot();
                if (slot < 0) return; // no rod, do nothing
                prevSlot = self.inventory.currentItem;
                rodSlot = slot;
                self.inventory.currentItem = rodSlot;
                sequenceTicks = 0;
                state = State.CAST;
                break;

            case CAST:
                if (!holdingRod()) { finish(); return; }
                useItem();
                holdTimer = holdTicks;
                state = State.WAIT;
                break;

            case WAIT:
                if (--holdTimer > 0) return;
                if (self.fishEntity != null) {
                    state = State.REEL;
                } else {
                    finish();
                }
                break;

            case REEL:
                if (holdingRod() && self.fishEntity != null) useItem();
                finish();
                break;
        }
    }

    // ---- Targeting ----

    /** Nearest valid player along the crosshair ray, at or beyond minDistance. */
    private EntityPlayer findTarget() {
        EntityPlayer self = mc.thePlayer;
        Vec3 eye = self.getPositionEyes(1.0F);
        Vec3 look = self.getLook(1.0F);
        Vec3 end = eye.addVector(look.xCoord * maxDistance,
                                 look.yCoord * maxDistance,
                                 look.zCoord * maxDistance);

        EntityPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3 bestHit = null;

        for (EntityPlayer p : mc.theWorld.playerEntities) {
            if (p == self || p.isDead || p.getHealth() <= 0 || p.isSpectator()) continue;

            float b = p.getCollisionBorderSize();
            AxisAlignedBB box = p.getEntityBoundingBox().expand(b, b, b);
            MovingObjectPosition hit = box.calculateIntercept(eye, end);
            if (hit == null) continue;

            double d = eye.distanceTo(hit.hitVec);
            if (d < bestDist) {
                bestDist = d;
                best = p;
                bestHit = hit.hitVec;
            }
        }

        if (best == null || bestDist < minDistance) return null;
        // Line of sight: no solid block between eye and hit point
        if (mc.theWorld.rayTraceBlocks(eye, bestHit, false, true, false) != null) return null;
        return best;
    }

    // ---- Rod handling ----

    private int findRodSlot() {
        InventoryPlayer inv = mc.thePlayer.inventory;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.mainInventory[i];
            if (s != null && s.getItem() == Items.fishing_rod) return i;
        }
        return -1;
    }

    private boolean holdingRod() {
        ItemStack s = mc.thePlayer.inventory.getCurrentItem();
        return s != null && s.getItem() == Items.fishing_rod;
    }

    private void useItem() {
        ItemStack s = mc.thePlayer.inventory.getCurrentItem();
        if (s == null) return;
        if (mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, s)) {
            mc.entityRenderer.itemRenderer.resetEquippedProgress2();
        }
        mc.thePlayer.swingItem();
    }

    // ---- State exits ----

    /** Normal end of a sequence: restore slot, start cooldown. */
    private void finish() {
        restoreSlot();
        cooldown = cooldownTicks;
        state = State.IDLE;
        sequenceTicks = 0;
    }

    /** Safe exit on disable, GUI open, or world change. No cooldown penalty. */
    private void abort() {
        restoreSlot();
        state = State.IDLE;
        sequenceTicks = 0;
    }

    private void resetState() {
        state = State.IDLE;
        prevSlot = rodSlot = -1;
        cooldown = holdTimer = sequenceTicks = 0;
    }

    private void restoreSlot() {
        if (mc.thePlayer != null && prevSlot >= 0 && prevSlot < 9) {
            mc.thePlayer.inventory.currentItem = prevSlot;
        }
        prevSlot = rodSlot = -1;
    }
}
