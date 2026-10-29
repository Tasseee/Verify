package dev.dtap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

public class DTapClient implements ClientModInitializer {
    static final float SAFETY = 1.15f;

    enum S { IDLE, HIT, OBSI, CRYSTAL, BREAK, WAIT_POP, HIT2 }
    record Plan(BlockPos base, BlockPos support, Direction face, float dmg) { }

    static S state = S.IDLE;
    static KeyBinding toggleKey, diffKey;
    static PlayerEntity target;
    static Plan plan;
    static boolean popped, greeted;
    static int timer, total, retries, completedTaps;
    // 1 = Single, 2 = Double, 3 = Triple. Im Menue frei waehlbar.
    static int desiredTaps = 2;
    // Item-IDs, bei denen DTap nicht gestartet werden darf, solange das Item gehalten wird.
    static final Set<String> blockedItems = new LinkedHashSet<>();
    // Separat: Items, die als gedroppte Stacks am Boden vor Explosionen geschuetzt werden.
    static final Set<String> groundBlockedItems = new LinkedHashSet<>();
    static boolean selfProtection = true;
    static boolean groundItemProtection = true;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.dtap.toggle", GLFW.GLFW_KEY_H, KeyBinding.Category.MISC));
        diffKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.dtap.difficulty", GLFW.GLFW_KEY_J, KeyBinding.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(DTapClient::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommandManager.literal("dtap")
                .executes(ctx -> { MinecraftClient.getInstance().setScreen(new DTapScreen()); return 1; })
                .then(ClientCommandManager.literal("blockitem")
                    .then(ClientCommandManager.argument("item", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(ctx -> {
                            String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "item");
                            Identifier parsed = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
                            if (parsed == null || !Registries.ITEM.containsId(parsed)) {
                                msg(MinecraftClient.getInstance(), "Unbekanntes Item: " + id);
                                return 0;
                            }
                            blockedItems.add(parsed.toString());
                            msg(MinecraftClient.getInstance(), "DTap blockiert bei gehaltenem Item: " + parsed);
                            return 1;
                        })))
                .then(ClientCommandManager.literal("unblockitem")
                    .then(ClientCommandManager.argument("item", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(ctx -> {
                            String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "item");
                            Identifier parsed = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
                            if (parsed == null || !blockedItems.remove(parsed.toString())) {
                                msg(MinecraftClient.getInstance(), "Item war nicht blockiert: " + id);
                                return 0;
                            }
                            msg(MinecraftClient.getInstance(), "DTap-Itemblock entfernt: " + parsed);
                            return 1;
                        })))
                .then(ClientCommandManager.literal("blockeditems").executes(ctx -> {
                    msg(MinecraftClient.getInstance(), blockedItems.isEmpty() ? "Keine Items blockiert" : "Blockiert: " + String.join(", ", blockedItems));
                    return 1;
                }))));

        // Explosions-Check: immer aktiv
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient() || !(entity instanceof EndCrystalEntity)) return ActionResult.PASS;
            float dmg = DamageCalculator.crystalDamage(player,
                new Vec3d(entity.getX(), entity.getY(), entity.getZ()), world);
            float hp = player.getHealth() + player.getAbsorptionAmount();
            if (selfProtection && dmg * SAFETY >= hp && !hasTotem(player)) {
                MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                    Text.literal("Crystal blockiert! Schaden ca. " + Math.round(dmg) + " / Leben " + Math.round(hp)
                        + " (" + DamageCalculator.current(world).name() + ")"), false);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }

    // vom Mixin: Totem-Pop (Status 35)
    public static void onPop(Entity e) {
        if (state != S.IDLE && e == target) popped = true;
    }

    static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            greeted = false;
            stop();
            return;
        }
        if (!greeted) { greeted = true; msg(mc, "DTap-Mod geladen (Taste H)"); }

        // J: Schwierigkeit umschalten AUTO -> EASY -> NORMAL -> HARD -> AUTO
        while (diffKey.wasPressed()) {
            Difficulty cur = DamageCalculator.override;
            DamageCalculator.override = cur == null ? Difficulty.EASY
                : cur == Difficulty.EASY ? Difficulty.NORMAL
                : cur == Difficulty.NORMAL ? Difficulty.HARD : null;
            msg(mc, "Schwierigkeit: " + (DamageCalculator.override == null ? "AUTO" : DamageCalculator.override.name())
                + " (Server meldet: " + mc.world.getDifficulty().name() + ")");
        }

        while (toggleKey.wasPressed()) {
            if (state != S.IDLE) { msg(mc, "DTap: abgebrochen"); stop(); }
            else start(mc);
        }
        if (state == S.IDLE) return;

        total++;
        timer++;
        if (target == null || total > 200) { abort(mc, "Zeitlimit"); return; }

        switch (state) {
            case HIT -> {
                if (mc.player.getAttackCooldownProgress(0.5f) < 0.9f && timer < 15) return;
                if (mc.player.distanceTo(target) <= 3.3f) {
                    if (!smoothLook(mc, target.getEyePos())) return;
                    if (isHeldItemBlocked(mc)) { abort(mc, "gehaltenes Item ist per /dtap blockitem gesperrt"); return; }
                    int w = findSlot(mc, st -> st.isIn(ItemTags.SWORDS) || st.isIn(ItemTags.AXES));
                    if (w >= 0) select(mc, w);
                    mc.interactionManager.attackEntity(mc.player, target);
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                state = S.OBSI;
                timer = 0;
            }
            case HIT2 -> {
                // Triple-Tap: nach den ersten zwei Crystal-Angriffen den Gegner erneut
                // in der Luft treffen, danach ein neues Obsidian-Setup an seiner Flugbahn.
                if (desiredTaps != 3) { state = S.OBSI; timer = 0; return; }
                if (target.isOnGround()) { abort(mc, "Triple abgebrochen: Gegner hat den Boden beruehrt"); return; }
                if (mc.player.getAttackCooldownProgress(0.5f) < 0.9f && timer < 15) return;
                if (mc.player.distanceTo(target) > 3.3f) {
                    abort(mc, "Triple abgebrochen: Gegner ausser Schlagreichweite"); return;
                }
                if (!smoothLook(mc, target.getEyePos())) return;
                int w = findSlot(mc, st -> st.isIn(ItemTags.SWORDS) || st.isIn(ItemTags.AXES));
                if (w < 0) { abort(mc, "kein Schwert/Axt in der Hotbar"); return; }
                select(mc, w);
                mc.interactionManager.attackEntity(mc.player, target);
                mc.player.swingHand(Hand.MAIN_HAND);
                plan = null;
                state = S.OBSI;
                timer = 0;
            }
            case OBSI -> {
                // Plan nur einmal waehlen. Neu berechnen bei jedem Tick fuehrte dazu,
                // dass die Rotation zwischen mehreren gleich guten Bloecken hin- und hersprang.
                if (plan == null) plan = bestPlan(mc, true);
                if (plan == null) { abort(mc, "kein sicherer Platz gefunden"); return; }
                if (mc.world.getBlockState(plan.base()).isAir()) {
                    int o = findSlot(mc, st -> st.isOf(Items.OBSIDIAN));
                    if (o < 0) { abort(mc, "kein Obsidian in der Hotbar"); return; }
                    select(mc, o);
                    Direction f = plan.face();
                    if (!smoothLook(mc, Vec3d.ofCenter(plan.support()))) return;
                    Vec3d hit = Vec3d.ofCenter(plan.support()).add(f.getOffsetX() * 0.5, f.getOffsetY() * 0.5, f.getOffsetZ() * 0.5);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                        new BlockHitResult(hit, f, plan.support(), false));
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                state = S.CRYSTAL;
                timer = 0;
            }
            case CRYSTAL -> {
                var b = mc.world.getBlockState(plan.base());
                if (!(b.isOf(Blocks.OBSIDIAN) || b.isOf(Blocks.BEDROCK))) {
                    if (timer < 6) return;
                    abort(mc, "Obsidian wurde nicht platziert");
                    return;
                }
                int c = findSlot(mc, st -> st.isOf(Items.END_CRYSTAL));
                if (c < 0) { abort(mc, "kein End Crystal in der Hotbar"); return; }
                select(mc, c);
                if (!smoothLook(mc, Vec3d.ofCenter(plan.base()).add(0, 1, 0))) return;
                // Vor jedem Crystal-Schritt die Selbstschadensprüfung erneut ausführen.
                float selfDmg = DamageCalculator.crystalDamage(mc.player, Vec3d.ofCenter(plan.base()).add(0, 1, 0), mc.world);
                if (selfProtection && selfDmg * SAFETY >= mc.player.getHealth() + mc.player.getAbsorptionAmount() && !hasTotem(mc.player)) {
                    abort(mc, "Crystal wegen Selbstschaden blockiert"); return;
                }
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(plan.base()).add(0, 0.5, 0), Direction.UP, plan.base(), false));
                mc.player.swingHand(Hand.MAIN_HAND);
                // Genau einmal platzieren, danach in BREAK auf die Server-Entity warten.
                state = S.BREAK;
                timer = 0;
            }
            case BREAK -> {
                Box area = new Box(plan.base().up()).expand(0.5);
                List<EndCrystalEntity> list = mc.world.getEntitiesByClass(EndCrystalEntity.class, area, e -> true);
                if (!list.isEmpty()) {
                    EndCrystalEntity crystal = list.get(0);
                    Vec3d crystalPos = new Vec3d(crystal.getX(), crystal.getY(), crystal.getZ());
                    if (groundItemProtection && nearDroppedItems(mc, crystalPos)) {
                        abort(mc, "Crystal blockiert: gedroppte Items koennten zerstoert werden"); return;
                    }
                    float selfDmg = DamageCalculator.crystalDamage(mc.player, crystalPos, mc.world);
                    if (selfProtection && selfDmg * SAFETY >= mc.player.getHealth() + mc.player.getAbsorptionAmount() && !hasTotem(mc.player)) {
                        abort(mc, "Crystal-Explosion wegen Selbstschaden blockiert"); return;
                    }
                    if (!smoothLook(mc, crystal.getEyePos())) return;
                    popped = false;
                    mc.interactionManager.attackEntity(mc.player, crystal);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    completedTaps++;
                    if (desiredTaps == 3 && completedTaps == 2) {
                        // Triple ist nicht "3 Crystals am selben Platz": zweiter Sword-Hit
                        // in der Luft, danach neues Obsidian und der dritte Crystal.
                        state = S.HIT2;
                        timer = 0;
                    } else if (completedTaps >= desiredTaps) {
                        finish(mc, "DTap fertig (" + completedTaps + " Crystal-Angriffe)");
                    } else {
                        state = S.WAIT_POP;
                        timer = 0;
                    }
                } else if (timer > 20) {
                    abort(mc, "Crystal nicht gefunden");
                }
            }
            case WAIT_POP -> {
                if (!target.isAlive() || target.getHealth() <= 0) { finish(mc, "Gegner besiegt"); return; }
                // Ein Pop beschleunigt den naechsten Tap. Triple-Tap bleibt aber nicht
                // haengen, wenn der Server keinen Pop-Status meldet.
                if (popped || timer > 6) {
                    retries = 0;
                    // Beim zweiten Crystal des Double/Triple zuerst den gleichen Base-Spot
                    // erneut versuchen. Nur wenn er nicht mehr gueltig ist, neu suchen.
                    boolean baseValid = plan != null &&
                        (mc.world.getBlockState(plan.base()).isOf(Blocks.OBSIDIAN) ||
                         mc.world.getBlockState(plan.base()).isOf(Blocks.BEDROCK));
                    if (!baseValid) plan = bestPlan(mc, true);
                    if (plan == null) { abort(mc, "kein sicherer Platz fuer weiteren Crystal"); return; }
                    state = S.CRYSTAL;
                    timer = 0;
                }
            }
            default -> { }
        }
    }

    static void start(MinecraftClient mc) {
        PlayerEntity best = null;
        double bd = 36;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator() || !p.isAlive()) continue;
            double d = mc.player.squaredDistanceTo(p);
            if (d < bd) { bd = d; best = p; }
        }
        if (best == null) { msg(mc, "DTap: kein Gegner in 6 Bloecken"); return; }
        if (isHeldItemBlocked(mc)) {
            msg(mc, "DTap blockiert: gehaltenes Item steht auf der Blockliste (/dtap blockeditems)");
            return;
        }
        target = best;
        state = S.HIT;
        timer = 0; total = 0; retries = 0;
        popped = false; plan = null; completedTaps = 0;
        msg(mc, "DTap: laeuft");
    }

    // beste Position fuer Obsidian/Bedrock + Crystal
    static Plan bestPlan(MinecraftClient mc, boolean allowPlace) {
        World w = mc.world;
        BlockPos t = target.getBlockPos();
        Vec3d eye = mc.player.getEyePos();
        Plan best = null;
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = -1; dy <= 0; dy++) {
                    BlockPos b = t.add(dx, dy, dz);
                    // Crystal muss in Schlagreichweite liegen
                    if (eye.squaredDistanceTo(b.getX() + 0.5, b.getY() + 1.5, b.getZ() + 0.5) > 12) continue;
                    var st = w.getBlockState(b);
                    boolean solid = st.isOf(Blocks.OBSIDIAN) || st.isOf(Blocks.BEDROCK);
                    BlockPos support = null;
                    Direction face = null;
                    if (!solid) {
                        if (!allowPlace || !st.isAir()) continue;
                        for (Direction d : Direction.values()) {
                            if (d == Direction.UP) continue;
                            BlockPos n = b.offset(d);
                            if (!w.getBlockState(n).isAir() && w.getFluidState(n).isEmpty()) {
                                support = n.toImmutable();
                                face = d.getOpposite();
                                break;
                            }
                        }
                        if (support == null) continue;
                        if (!w.getOtherEntities(null, new Box(b), e -> !e.isSpectator()).isEmpty()) continue;
                    }
                    if (!w.isAir(b.up()) || !w.isAir(b.up(2))) continue;
                    // Vanilla prueft beim Crystal-Platzieren eine 1x2x1-Box ueber dem Block
                    if (!w.getOtherEntities(null, new Box(b.getX(), b.getY() + 1, b.getZ(), b.getX() + 1, b.getY() + 3, b.getZ() + 1), e -> !e.isSpectator()).isEmpty()) continue;

                    Vec3d pos = new Vec3d(b.getX() + 0.5, b.getY() + 1, b.getZ() + 0.5);
                    float dmg = DamageCalculator.crystalDamage(target, pos, w);
                    if (dmg <= 0 || (best != null && dmg <= best.dmg())) continue;
                    float self = DamageCalculator.crystalDamage(mc.player, pos, w);
                    if (selfProtection && selfDies(mc.player, self)) continue;
                    best = new Plan(b.toImmutable(), support, face, dmg);
                }
        return best;
    }

    // End-Crystal-Explosionen koennen gedroppte ItemStacks zerstoeren. Da Ownership
    // clientseitig nicht verlaesslich erkennbar ist, schuetzen wir alle nahen Drops.
    static boolean nearDroppedItems(MinecraftClient mc, Vec3d crystalPos) {
        if (groundBlockedItems.isEmpty()) return false;
        Box area = new Box(crystalPos.x - 6.0, crystalPos.y - 5.0, crystalPos.z - 6.0,
                           crystalPos.x + 6.0, crystalPos.y + 5.0, crystalPos.z + 6.0);
        return !mc.world.getEntitiesByClass(ItemEntity.class, area, item -> {
            if (!item.isAlive() || item.squaredDistanceTo(crystalPos) > 36.0) return false;
            String id = Registries.ITEM.getId(item.getStack().getItem()).toString();
            return groundBlockedItems.contains(id);
        }).isEmpty();
    }

    static boolean isHeldItemBlocked(MinecraftClient mc) {
        if (mc.player == null || blockedItems.isEmpty()) return false;
        ItemStack main = mc.player.getMainHandStack();
        return !main.isEmpty() && blockedItems.contains(Registries.ITEM.getId(main.getItem()).toString());
    }

    // Setzt die Client-Rotation, damit Interaktionen und Schläge sichtbar korrekt ausgerichtet sind.
    static boolean smoothLook(MinecraftClient mc, Vec3d pos) {
        if (mc.player == null) return false;
        Vec3d delta = pos.subtract(mc.player.getEyePos());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float)(MathHelper.atan2(delta.z, delta.x) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float)(-(MathHelper.atan2(delta.y, horizontal) * 180.0 / Math.PI));
        float dy = MathHelper.wrapDegrees(yaw - mc.player.getYaw());
        float dp = pitch - mc.player.getPitch();
        // Kleinere Rotationsschritte vermeiden harte Kamera-Spruenge.
        if (Math.abs(dy) < 1.2f && Math.abs(dp) < 1.2f) {
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
            return true;
        }
        mc.player.setYaw(mc.player.getYaw() + MathHelper.clamp(dy, -4.0f, 4.0f));
        mc.player.setPitch(mc.player.getPitch() + MathHelper.clamp(dp, -3.5f, 3.5f));
        return false;
    }

    static int findSlot(MinecraftClient mc, Predicate<ItemStack> f) {
        for (int i = 0; i < 9; i++)
            if (f.test(mc.player.getInventory().getStack(i))) return i;
        return -1;
    }

    static void select(MinecraftClient mc, int slot) {
        mc.player.getInventory().setSelectedSlot(slot);
    }

    static boolean hasTotem(PlayerEntity p) {
        return p.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING) || p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
    }

    static boolean selfDies(PlayerEntity me, float dmg) {
        return dmg * SAFETY >= me.getHealth() + me.getAbsorptionAmount() && !hasTotem(me);
    }

    static void msg(MinecraftClient mc, String s) {
        if (mc.inGameHud != null) mc.inGameHud.setOverlayMessage(Text.literal(s), false);
    }

    static void abort(MinecraftClient mc, String why) { msg(mc, "DTap abgebrochen: " + why); stop(); }
    static void finish(MinecraftClient mc, String s) { msg(mc, "DTap: " + s); stop(); }
    static void stop() { state = S.IDLE; target = null; plan = null; popped = false; }
}
