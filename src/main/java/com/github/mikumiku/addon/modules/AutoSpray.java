package com.github.mikumiku.addon.modules;


import com.github.mikumiku.addon.BaseModule;
import com.github.mikumiku.addon.util.Via;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class AutoSpray extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargets = settings.createGroup("喷什么");
    private final SettingGroup sgConditions = settings.createGroup("什么情况喷");
    private final SettingGroup sgBinds = settings.createGroup("快捷键");

    // 常规设置 (General)
    private final Setting<Double> delay = sgGeneral.add(new DoubleSetting.Builder()
        .name("延迟 (s)")
        .description("两次投掷药水之间的延迟（秒）。")
        .defaultValue(0.5)
        .min(0.0).max(10.0)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("旋转视角")
        .description("投掷药水时是否向下看。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> pitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("俯仰角")
        .description("投掷时的视角俯仰角度。")
        .defaultValue(90.0)
        .min(70.0).max(90.0)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Boolean> raytrace = sgGeneral.add(new BoolSetting.Builder()
        .name("轨迹预测")
        .description("计算抛物线与运动轨迹，确保药水能砸中自己。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> predictTicks = sgGeneral.add(new IntSetting.Builder()
        .name("预测刻")
        .description("预测自己未来的位置刻数。")
        .defaultValue(2)
        .min(0).max(10)
        .visible(raytrace::get)
        .build()
    );

    private final Setting<Double> effectRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("有效范围")
        .description("预测落点与自身的最大允许距离。")
        .defaultValue(3.0)
        .min(0.0).max(6.0)
        .visible(raytrace::get)
        .build()
    );

    // 目标效果 (Targets)
    private final Setting<Boolean> resistance = sgTargets.add(new BoolSetting.Builder().name("自动神龟").defaultValue(false).build());
    private final Setting<Boolean> strength = sgTargets.add(new BoolSetting.Builder().name("自动力量").defaultValue(false).build());
    private final Setting<Boolean> speed = sgTargets.add(new BoolSetting.Builder().name("自动速度").defaultValue(false).build());
    private final Setting<Boolean> slowFalling = sgTargets.add(new BoolSetting.Builder().name("自动缓降").defaultValue(false).build());

    // 条件检测 (Conditions)
    private final Setting<Boolean> healthCheck = sgConditions.add(new BoolSetting.Builder()
        .name("生命值检测 (抗性)")
        .description("仅当生命值低于设定值时才投掷抗性药水。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> health = sgConditions.add(new DoubleSetting.Builder()
        .name("生命值阈值")
        .description("触发抗性药水的最低血量（含黄心）。")
        .defaultValue(17.0)
        .min(1.0).max(36.0)
        .visible(healthCheck::get)
        .build()
    );

    private final Setting<Boolean> onlyPlayerNearby = sgConditions.add(new BoolSetting.Builder()
        .name("附近有敌人才喷")
        .description("仅当附近有敌对玩家时才投掷药水。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> playerRange = sgConditions.add(new DoubleSetting.Builder()
        .name("敌人检测范围")
        .description("检测敌对玩家的范围（格）。")
        .defaultValue(16.0)
        .min(4.0).max(64.0)
        .visible(onlyPlayerNearby::get)
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgConditions.add(new BoolSetting.Builder()
        .name("忽略好友")
        .description("将好友列表中的玩家视为非敌人。")
        .defaultValue(true)
        .visible(onlyPlayerNearby::get)
        .build()
    );

    private final Setting<Boolean> earlyThrow = sgConditions.add(new BoolSetting.Builder()
        .name("提前续杯")
        .description("在药水效果结束前提前投掷刷新时间。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> earlyThrowTime = sgConditions.add(new DoubleSetting.Builder()
        .name("提前时间 (s)")
        .description("剩余多少秒时提前扔出药水。")
        .defaultValue(0.4)
        .min(0.0).max(5.0)
        .visible(earlyThrow::get)
        .build()
    );

    private final Setting<Boolean> usingPause = sgConditions.add(new BoolSetting.Builder()
        .name("吃东西时别喷")
        .description("当你正在吃东西或拉弓时暂停投掷。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyGround = sgConditions.add(new BoolSetting.Builder()
        .name("空中别喷")
        .description("仅当你站在地面上时才自动投掷。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> inventorySearch = sgConditions.add(new BoolSetting.Builder()
        .name("检索背包")
        .description("当快捷栏没有对应药水时，从背包中检索并替换。")
        .defaultValue(true)
        .build()
    );

    // 快捷键 (Keybinds) - 使用 Meteor 的 KeybindSetting
    private final Setting<Keybind> strengthKey = sgBinds.add(new KeybindSetting.Builder().name("力量快捷键").defaultValue(Keybind.none()).build());
    private final Setting<Keybind> resistanceKey = sgBinds.add(new KeybindSetting.Builder().name("神龟快捷键").defaultValue(Keybind.none()).build());
    private final Setting<Keybind> speedKey = sgBinds.add(new KeybindSetting.Builder().name("速度快捷键").defaultValue(Keybind.none()).build());

    private long lastThrowTime = 0;
    private boolean turtlePress;
    private boolean speedPress;
    private boolean strengthPress;

    public AutoSpray() {
        super(BaseModule.CATEGORY_MIKU_COMBAT, "自动喷药", "高级自动药水投掷。");
    }

    @Override
    public void onDeactivate() {
        turtlePress = false;
        speedPress = false;
        strengthPress = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        handleKeybinds();

        if (System.currentTimeMillis() - lastThrowTime < delay.get() * 1000.0) return;

        if (usingPause.get() && mc.player.isUsingItem()) return;

        if (onlyGround.get() && !mc.player.isOnGround()) {
            return; // 简化版地面检测，若需更严谨可加入方块碰撞检测
        }

        // 检查附近是否有敌人
        if (onlyPlayerNearby.get() && !isEnemyNearby()) {
            return;
        }

        checkAndThrow(StatusEffects.RESISTANCE, resistance.get(), true);
        checkAndThrow(StatusEffects.SPEED, speed.get(), false);
        checkAndThrow(StatusEffects.STRENGTH, strength.get(), false);
        checkAndThrow(StatusEffects.SLOW_FALLING, slowFalling.get(), false);
    }

    private void handleKeybinds() {
        if (resistanceKey.get().isPressed()) {
            if (!turtlePress && canThrowPotion(StatusEffects.RESISTANCE)) {
                executeThrow(StatusEffects.RESISTANCE);
                turtlePress = true;
            }
        } else turtlePress = false;

        if (strengthKey.get().isPressed()) {
            if (!strengthPress && canThrowPotion(StatusEffects.STRENGTH)) {
                executeThrow(StatusEffects.STRENGTH);
                strengthPress = true;
            }
        } else strengthPress = false;

        if (speedKey.get().isPressed()) {
            if (!speedPress && canThrowPotion(StatusEffects.SPEED)) {
                executeThrow(StatusEffects.SPEED);
                speedPress = true;
            }
        } else speedPress = false;
    }

    private void checkAndThrow(RegistryEntry<StatusEffect> effect, boolean isEnabled, boolean isResistance) {
        if (!isEnabled || System.currentTimeMillis() - lastThrowTime < delay.get() * 1000.0) return;

        StatusEffectInstance currentEffect = mc.player.getStatusEffect(effect);
        boolean hasEffect = currentEffect != null;
        boolean shouldThrow = false;

        // 特殊处理抗性（涉及血量检测）
        if (isResistance) {
            boolean healthLow = !healthCheck.get() || (mc.player.getHealth() + mc.player.getAbsorptionAmount()) <= health.get();
            if (earlyThrow.get() && hasEffect && currentEffect.getDuration() <= earlyThrowTime.get() * 20.0) {
                shouldThrow = true;
            }
            if (!hasEffect || (healthLow && currentEffect.getAmplifier() < 1)) {
                shouldThrow = true;
            }
        } else {
            // 普通效果处理
            if (earlyThrow.get() && hasEffect && currentEffect.getDuration() <= earlyThrowTime.get() * 20.0) {
                shouldThrow = true;
            }
            if (!hasEffect) {
                shouldThrow = true;
            }
        }

        if (shouldThrow && canThrowPotion(effect)) {
            executeThrow(effect);
        }
    }

    private boolean canThrowPotion(RegistryEntry<StatusEffect> effect) {
        if (raytrace.get()) {
            Vec3d hitPos = calcTrajectory(mc.player.getYaw(), pitch.get().floatValue());
            if (hitPos == null) return false;

            Vec3d playerFuturePos = getPredictedPos(mc.player, predictTicks.get());
            if (playerFuturePos.squaredDistanceTo(hitPos) > effectRange.get() * effectRange.get()) {
                return false;
            }
        }

        FindItemResult item = getPotionItem(effect);
        return item.found();
    }

    private void executeThrow(RegistryEntry<StatusEffect> effect) {
        FindItemResult item = getPotionItem(effect);
        if (!item.found()) return;

        Runnable throwAction = () -> {
            // Meteor 的 InvUtils 自动处理背包物品切换/替换，并且支持投掷后切回原物品 (swapBack)
            InvUtils.swap(item.slot(), true);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
            lastThrowTime = System.currentTimeMillis();
        };

        if (rotate.get()) {
            Rotations.rotate(mc.player.getYaw(), pitch.get().floatValue(), throwAction);
        } else {
            throwAction.run();
        }
    }

    // --- 工具方法区 ---

    /**
     * 检测附近是否有敌对玩家
     *
     * @return true 表示附近有敌人
     */
    private boolean isEnemyNearby() {
        for (PlayerEntity player : mc.world.getPlayers()) {
            // 跳过自己
            if (player.getUuid().equals(mc.player.getUuid())) {
                continue;
            }

            // 忽略好友
            if (ignoreFriends.get() && Friends.get().isFriend(player)) {
                continue;
            }

            // 检查距离
            double distance = mc.player.distanceTo(player);
            if (distance <= playerRange.get()) {
                return true;
            }
        }
        return false;
    }

    private FindItemResult getPotionItem(RegistryEntry<StatusEffect> targetEffect) {
        return inventorySearch.get()
            ? InvUtils.find(stack -> isTargetPotion(stack, targetEffect))
            : InvUtils.findInHotbar(stack -> isTargetPotion(stack, targetEffect));
    }

    private boolean isTargetPotion(ItemStack stack, RegistryEntry<StatusEffect> targetEffect) {
        if (stack.getItem() != Items.SPLASH_POTION) return false;

        // 适配 1.20.5+ 的 Data Component
        PotionContentsComponent contents = stack.getOrDefault(DataComponentTypes.POTION_CONTENTS, PotionContentsComponent.DEFAULT);
        for (StatusEffectInstance effectInstance : contents.getEffects()) {
            if (effectInstance.getEffectType() == targetEffect) {
                return true;
            }
        }
        return false;
    }

    private Vec3d getPredictedPos(Entity entity, int ticks) {
        if (ticks == 0) return Via.getEntityPos(entity);

        Vec3d motion = entity.getVelocity();
        return Via.getEntityPos(entity).add(motion.multiply(ticks));
    }

    // 完全还原抛物线轨迹模拟 (基于原版重力/摩擦力计算提取)
    private Vec3d calcTrajectory(float yaw, float pitch) {
//        float tickDelta = mc.getRenderTickCounter().getTickDelta(true);
//        double x = MathHelper.lerp(tickDelta, mc.player.prevX, mc.player.getX());
//        double y = MathHelper.lerp(tickDelta, mc.player.prevY, mc.player.getY()) + mc.player.getEyeHeight(mc.player.getPose()) - 0.1;
//        double z = MathHelper.lerp(tickDelta, mc.player.prevZ, mc.player.getZ());
//
//        x -= MathHelper.cos(yaw / 180.0F * (float) Math.PI) * 0.16F;
//        z -= MathHelper.sin(yaw / 180.0F * (float) Math.PI) * 0.16F;
//
//        double motionX = -MathHelper.sin(yaw / 180.0F * (float) Math.PI) * MathHelper.cos(pitch / 180.0F * (float) Math.PI) * 0.4F;
//        double motionY = -MathHelper.sin((pitch - 20.0F) / 180.0F * (float) Math.PI) * 0.4F;
//        double motionZ = MathHelper.cos(yaw / 180.0F * (float) Math.PI) * MathHelper.cos(pitch / 180.0F * (float) Math.PI) * 0.4F;
//
//        float distance = MathHelper.sqrt((float) (motionX * motionX + motionY * motionY + motionZ * motionZ));
//        motionX /= distance;
//        motionY /= distance;
//        motionZ /= distance;
//
//        motionX *= 0.5;
//        motionY *= 0.5;
//        motionZ *= 0.5;
//
//        if (!mc.player.isOnGround()) {
//            motionY += mc.player.getVelocity().y;
//        }
//
//        for (int i = 0; i < 300; i++) {
//            Vec3d lastPos = new Vec3d(x, y, z);
//            x += motionX;
//            y += motionY;
//            z += motionZ;
//
//            // 如果砸到水中，阻力变大
//            if (mc.world.getBlockState(net.minecraft.util.math.BlockPos.ofFloored(x, y, z)).isOf(net.minecraft.block.Blocks.WATER)) {
//                motionX *= 0.8;
//                motionY *= 0.8;
//                motionZ *= 0.8;
//            } else {
//                motionX *= 0.99; // 空气阻力
//                motionY *= 0.99;
//                motionZ *= 0.99;
//            }
//
//            motionY -= 0.03F; // 重力加速度
//
//            Vec3d pos = new Vec3d(x, y, z);
//            BlockHitResult bhr = mc.world.raycast(new RaycastContext(lastPos, pos, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
//
//            if (bhr != null && bhr.getType() == HitResult.Type.BLOCK) {
//                return bhr.getPos();
//            }
//        }
        return null;
    }
}
