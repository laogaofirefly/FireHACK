package com.github.mikumiku.addon.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.ICustomGoalProcess;
import com.github.mikumiku.addon.BaseModule;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

import java.util.List;

/**
 * Baritone 快捷操作模块
 * 所有按钮均使用 BoolSetting + onChanged 回调实现；
 * 快捷键使用 KeybindSetting<Keybind> + Tick 轮询实现。
 */
public class BaritoneHelperModule extends BaseModule {

    // ============================================================
    //  设置分组
    // ============================================================
    private final SettingGroup sgNavigation = settings.createGroup("寻路功能");
    private final SettingGroup sgSpecial = settings.createGroup("特殊功能");
    private final SettingGroup sgControl = settings.createGroup("控制操作");
    private final SettingGroup sgKeybinds = settings.createGroup("快捷键");

    // ============================================================
    //  自选方块
    // ============================================================

    /**
     * 自选方块列表（getToBlock 使用）
     */
    private final Setting<List<Block>> targetBlocks = sgNavigation.add(
        new BlockListSetting.Builder()
            .name("目标方块")
            .description("「寻路到自选方块」的目标方块，寻路时取列表第一项")
            .defaultValue(List.of(Blocks.ENDER_CHEST))
            .build()
    );

    /**
     * goto 目标坐标 X
     */
    private final Setting<Integer> gotoX = sgNavigation.add(
        new IntSetting.Builder()
            .name("坐标 X")
            .description("「前往指定坐标」的 X 轴目标值")
            .defaultValue(0)
            .sliderRange(-30000000, 30000000)
            .noSlider()
            .build()
    );

    /**
     * goto 目标坐标 Y
     */
    private final Setting<Integer> gotoY = sgNavigation.add(
        new IntSetting.Builder()
            .name("坐标 Y")
            .description("「前往指定坐标」的 Y 轴目标值（设为 -1 则忽略 Y 轴限制）")
            .defaultValue(64)
            .range(-64, 320)
            .sliderRange(-64, 320)
            .build()
    );

    /**
     * goto 目标坐标 Z
     */
    private final Setting<Integer> gotoZ = sgNavigation.add(
        new IntSetting.Builder()
            .name("坐标 Z")
            .description("「前往指定坐标」的 Z 轴目标值")
            .defaultValue(0)
            .sliderRange(-30000000, 30000000)
            .noSlider()
            .build()
    );

    // ============================================================
    //  寻路按钮（BoolSetting 触发式，visible=false 隐藏状态值）
    // ============================================================

    // 1. 寻路到地狱门
    private final Setting<Boolean> btnNetherPortal = sgNavigation.add(
        new BoolSetting.Builder()
            .name("寻路到地狱门")
            .description("点击开启：令 Baritone 自动寻路前往最近的地狱门")
            .defaultValue(false)
            .onChanged(v -> {
                if (v) goToNetherPortal();
            })
            .build()
    );

    // 2. 寻路到末影箱
    private final Setting<Boolean> btnEnderChest = sgNavigation.add(
        new BoolSetting.Builder()
            .name("寻路到末影箱")
            .description("点击开启：令 Baritone 自动寻路前往最近的末影箱")
            .defaultValue(false)
            .onChanged(v -> {
                if (v) goToEnderChest();
            })
            .build()
    );

    // 3. 寻路到自选方块
    private final Setting<Boolean> btnCustomBlock = sgNavigation.add(
        new BoolSetting.Builder()
            .name("寻路到自选方块")
            .description("开启：令 Baritone 寻路前往上方「目标方块」中第一个方块")
            .defaultValue(false)
            .onChanged(v -> {
                if (v) goToCustomBlock();
            })
            .build()
    );

    /**
     * 4. 前往指定坐标（goto）
     */
    private final Setting<Boolean> btnGoto = sgNavigation.add(
        new BoolSetting.Builder()
            .name("前往指定坐标")
            .description("开启：令 Baritone 寻路前往上方填写的 X/Y/Z 坐标")
            .defaultValue(false)
            .onChanged(v -> {
                if (v) gotoCoords();
            })
            .build()
    );

    // ============================================================
    //  特殊功能 — 参数设置
    // ============================================================

    /**
     * mine 目标方块列表
     */
    private final Setting<List<Block>> mineBlocks = sgSpecial.add(
        new BlockListSetting.Builder()
            .name("挖掘目标方块")
            .description("「挖掘并收集方块」的目标方块列表，Baritone 会持续寻找并挖掘这些方块")
            .defaultValue(List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE))
            .build()
    );

    // ============================================================
    //  特殊功能按钮
    // ============================================================


    /**
     * 6. 挖掘并收集方块（mine）
     */
    private final Setting<Boolean> btnMine = sgSpecial.add(
        new BoolSetting.Builder()
            .name("挖掘并收集方块")
            .description("开启：令 Baritone 持续搜寻并挖掘上方「挖掘目标方块」列表中的所有方块")
            .defaultValue(false)
            .onChanged(v -> {
                if (v) startMine();
                else stopMine();
            })
            .build()
    );

    /**
     * 7. 识别并耕种/采摘作物（farm）
     */
    private final Setting<Boolean> btnFarm = sgSpecial.add(
        new BoolSetting.Builder()
            .name("耕种/采摘作物")
            .description("开启：令 Baritone 自动识别周围成熟作物并进行采摘与补种（farm 模式）")
            .defaultValue(false)
            .onChanged(v -> {
                if (v) startFarm();
                else stopFarm();
            })
            .build()
    );

    // ============================================================
    //  控制按钮
    // ============================================================

    // 4. 取消所有任务
    private final Setting<Boolean> btnCancelAll = sgControl.add(
        new BoolSetting.Builder()
            .name("取消所有任务")
            .description("点击：立即停止 Baritone 的全部任务")
            .defaultValue(false)
            .visible(() -> false)
            .onChanged(v -> {
                if (v) {
                    cancelAll();
                }
            })
            .build()
    );

    // 5. 暂停
    private final Setting<Boolean> btnPause = sgControl.add(
        new BoolSetting.Builder()
            .name("暂停")
            .description("点击：暂停当前 Baritone 任务（保留任务记录以便继续）")
            .defaultValue(false)
            .visible(() -> false)
            .onChanged(v -> {
                if (v) {
                    pauseBaritone();
                }
            })
            .build()
    );

    // 6. 继续
    private final Setting<Boolean> btnResume = sgControl.add(
        new BoolSetting.Builder()
            .name("继续")
            .description("点击：恢复已暂停的 Baritone 任务")
            .defaultValue(false)
            .visible(() -> false)
            .onChanged(v -> {
                if (v) {
                    resumeBaritone();
                }
            })
            .build()
    );

    // ============================================================
    //  快捷键（使用 Keybind 类型）
    // ============================================================

    private final Setting<Keybind> keyNetherPortal = sgKeybinds.add(
        new KeybindSetting.Builder()
            .name("寻路到地狱门")
            .description("绑定一个按键来触发「寻路到地狱门」")
            .action(() -> goToNetherPortal())
            .build()
    );

    private final Setting<Keybind> keyEnderChest = sgKeybinds.add(
        new KeybindSetting.Builder()
            .name("寻路到末影箱")
            .description("绑定一个按键来触发「寻路到末影箱」")
            .action(() -> goToEnderChest())
            .build()
    );

    private final Setting<Keybind> keyCustomBlock = sgKeybinds.add(
        new KeybindSetting.Builder()
            .name("寻路到自选方块")
            .description("绑定按键来触发「寻路到自选方块」")
            .action(() -> goToCustomBlock())
            .build()
    );

    private final Setting<Keybind> keyGoto = sgKeybinds.add(
        new KeybindSetting.Builder()
            .name("前往指定坐标")
            .description("绑定按键来触发「前往指定坐标」")
            .action(() -> gotoCoords())
            .build()
    );

    private final Setting<Keybind> keyMine = sgKeybinds.add(
        new KeybindSetting.Builder()
            .name("挖掘并收集方块")
            .description("绑定按键来切换「挖掘并收集方块」")
            .action(() -> startMine())
            .build()
    );

    private final Setting<Keybind> keyFarm = sgKeybinds.add(
        new KeybindSetting.Builder()
            .name("耕种/采摘作物")
            .description("绑定按键来切换「耕种/采摘作物」")
            .action(() -> startFarm())
            .build()
    );

    private final Setting<Keybind> keyCancelAll = sgKeybinds.add(
        new KeybindSetting.Builder()
            .name("取消所有任务")
            .description("绑定一个按键来触发「取消所有任务」")
            .action(() -> cancelAll())
            .build()
    );

    private final Setting<Keybind> keyPause = sgKeybinds.add(
        new KeybindSetting.Builder()
            .name("暂停")
            .description("绑定一个按键来触发「暂停」")
            .action(() -> pauseBaritone())
            .build()
    );

    private final Setting<Keybind> keyResume = sgKeybinds.add(
        new KeybindSetting.Builder()
            .name("继续")
            .description("绑定一个按键来触发「继续」")
            .action(() -> resumeBaritone())
            .build()
    );

    // ============================================================
    //  内部状态：记录当前激活的寻路类型（用于"继续"时恢复）
    // ============================================================

    /**
     * 所有可记录并恢复的任务类型
     */
    private enum NavTask {
        NONE,
        NETHER_PORTAL,
        ENDER_CHEST,
        CUSTOM_BLOCK,
        GOTO,
        COME,
        MINE,
        FARM
    }

    private NavTask lastNavTask = NavTask.NONE;
    private boolean isPaused = false;

    // ============================================================
    //  构造
    // ============================================================
    public BaritoneHelperModule() {
        super(
            "baritone助手",
            "Baritone 快捷操作：寻路到地狱门/末影箱/自选方块、挖矿、耕种、接近摄像机、前往坐标，以及暂停、继续、取消，全部支持快捷键绑定。"
        );
    }

    // ============================================================
    //  Tick 事件：轮询快捷键
    // ============================================================
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (keyNetherPortal.get().isPressed()) goToNetherPortal();
        if (keyEnderChest.get().isPressed()) goToEnderChest();
        if (keyCustomBlock.get().isPressed()) goToCustomBlock();
        if (keyGoto.get().isPressed()) gotoCoords();

        // mine / farm 是持续型开关，快捷键做切换
        if (keyMine.get().isPressed()) silentSet(btnMine, !btnMine.get());
        if (keyFarm.get().isPressed()) silentSet(btnFarm, !btnFarm.get());

        if (keyCancelAll.get().isPressed()) cancelAll();
        if (keyPause.get().isPressed()) pauseBaritone();
        if (keyResume.get().isPressed()) resumeBaritone();
    }

    // ============================================================
    //  功能实现
    // ============================================================

    /**
     * 1. 寻路到地狱门
     */
    private void goToNetherPortal() {
        if (mc.player == null || mc.world == null) return;
        resetNavButtons(btnNetherPortal);
        getBaritone().getGetToBlockProcess().getToBlock(Blocks.NETHER_PORTAL);
        setNavState(NavTask.NETHER_PORTAL);
        ChatUtils.info("§a[Baritone助手] §f开始寻路到 §b地狱门§f……");
    }

    /**
     * 2. 寻路到末影箱
     */
    private void goToEnderChest() {
        if (mc.player == null || mc.world == null) return;
        resetNavButtons(btnEnderChest);
        getBaritone().getGetToBlockProcess().getToBlock(Blocks.ENDER_CHEST);
        setNavState(NavTask.ENDER_CHEST);
        ChatUtils.info("§a[Baritone助手] §f开始寻路到 §b末影箱§f……");
    }

    /**
     * 3. 寻路到自选方块
     */
    private void goToCustomBlock() {
        if (mc.player == null || mc.world == null) return;
        List<Block> blocks = targetBlocks.get();
        if (blocks.isEmpty()) {
            ChatUtils.error("§c[Baritone助手] 请先在「目标方块」设置中选择至少一种方块！");
            silentSet(btnCustomBlock, false);
            return;
        }
        Block target = blocks.get(0);
        resetNavButtons(btnCustomBlock);
        getBaritone().getGetToBlockProcess().getToBlock(target);
        setNavState(NavTask.CUSTOM_BLOCK);
        ChatUtils.info("§a[Baritone助手] §f开始寻路到 §b" + target.getName().getString() + "§f……");
    }

    /**
     * 4. 前往指定坐标（goto）
     */
    private void gotoCoords() {
        if (mc.player == null || mc.world == null) return;
        int x = gotoX.get();
        int y = gotoY.get();
        int z = gotoZ.get();
        resetNavButtons(btnGoto);
        ICustomGoalProcess goalProc = getBaritone().getCustomGoalProcess();
        goalProc.setGoalAndPath(new GoalBlock(x, y, z));
        setNavState(NavTask.GOTO);
        ChatUtils.info(String.format("§a[Baritone助手] §f开始前往坐标 §b(%d, %d, %d)§f……", x, y, z));
    }

    // ============================================================
    //  功能实现 — 特殊
    // ============================================================


    /**
     * 6. 挖掘并收集方块（mine）—— 开启
     */
    private void startMine() {
        if (mc.player == null || mc.world == null) return;
        List<Block> blocks = mineBlocks.get();
        if (blocks.isEmpty()) {
            ChatUtils.error("§c[Baritone助手] 请先在「挖掘目标方块」中选择至少一种方块！");
            silentSet(btnMine, false);
            return;
        }
        // 关闭其他持续型任务
        silentSet(btnFarm, false);
        Block[] arr = blocks.toArray(new Block[0]);
        getBaritone().getMineProcess().mine(arr);
        setNavState(NavTask.MINE);
        ChatUtils.info("§a[Baritone助手] §f开始挖掘：§b" + blockListNames(blocks));
    }

    /**
     * 6. 挖掘并收集方块（mine）—— 关闭
     */
    private void stopMine() {
        if (lastNavTask == NavTask.MINE) {
            getBaritone().getPathingBehavior().cancelEverything();
            lastNavTask = NavTask.NONE;
            ChatUtils.info("§e[Baritone助手] §f已停止挖掘任务。");
        }
    }

    /**
     * 7. 识别并耕种/采摘作物（farm）—— 开启
     */
    private void startFarm() {
        if (mc.player == null || mc.world == null) return;
        // 关闭其他持续型任务
        silentSet(btnMine, false);
        getBaritone().getFarmProcess().farm();
        setNavState(NavTask.FARM);
        ChatUtils.info("§a[Baritone助手] §f开始耕种/采摘作物模式……");
    }

    /**
     * 7. 识别并耕种/采摘作物（farm）—— 关闭
     */
    private void stopFarm() {
        if (lastNavTask == NavTask.FARM) {
            getBaritone().getPathingBehavior().cancelEverything();
            lastNavTask = NavTask.NONE;
            ChatUtils.info("§e[Baritone助手] §f已停止耕种任务。");
        }
    }

    // ============================================================
    //  功能实现 — 控制
    // ============================================================

    /**
     * 8. 取消所有任务
     */
    private void cancelAll() {
        if (mc.player == null) return;
        getBaritone().getPathingBehavior().cancelEverything();
        lastNavTask = NavTask.NONE;
        isPaused = false;
        // 同步所有寻路开关关闭
        silentSet(btnNetherPortal, false);
        silentSet(btnEnderChest, false);
        silentSet(btnCustomBlock, false);
        silentSet(btnGoto, false);
        silentSet(btnMine, false);
        silentSet(btnFarm, false);
        ChatUtils.info("§e[Baritone助手] §f已取消所有任务。");
    }

    /**
     * 5. 暂停
     */
    private void pauseBaritone() {
        if (mc.player == null) return;
        if (isPaused) {
            ChatUtils.warning("§e[Baritone助手] §f当前已处于暂停状态。");
            return;
        }
        // 停止路径执行，但保留 lastNavTask 以便恢复
        getBaritone().getPathingBehavior().cancelEverything();
        isPaused = true;
        ChatUtils.info("§e[Baritone助手] §fBaritone 已 §c暂停§f（任务类型已记录）。");
    }

    /**
     * 6. 继续
     */
    private void resumeBaritone() {
        if (mc.player == null) return;
        if (!isPaused) {
            ChatUtils.warning("§e[Baritone助手] §fBaritone 当前并未处于暂停状态。");
            return;
        }
        isPaused = false;
        switch (lastNavTask) {
            case NETHER_PORTAL -> goToNetherPortal();
            case ENDER_CHEST -> goToEnderChest();
            case CUSTOM_BLOCK -> goToCustomBlock();
            case GOTO -> gotoCoords();
            case MINE -> {
                silentSet(btnMine, true);
                startMine();
            }
            case FARM -> {
                silentSet(btnFarm, true);
                startFarm();
            }
            default -> ChatUtils.warning("§e[Baritone助手] §f没有可恢复的任务，请重新开启寻路。");
        }
    }

    // ============================================================
    //  工具方法
    // ============================================================

    private IBaritone getBaritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /**
     * 记录当前导航任务类型，并重置暂停标志
     */
    private void setNavState(NavTask task) {
        lastNavTask = task;
        isPaused = false;
    }

    /**
     * 将所有寻路/特殊开关静默重置为 false，仅保留 keep 开关为其当前值。
     * 用于互斥逻辑：开启一个任务时关闭其他所有任务开关。
     */
    private void resetNavButtons(Setting<Boolean> keep) {
        Setting<Boolean>[] allNavBtns = new Setting[]{
            btnNetherPortal, btnEnderChest, btnCustomBlock,
            btnGoto, btnMine, btnFarm
        };
        for (Setting<Boolean> btn : allNavBtns) {
            if (btn != keep) silentSet(btn, false);
        }
        // 同时停止 Baritone 当前任务，避免多任务叠加
        getBaritone().getPathingBehavior().cancelEverything();
    }

    /**
     * 静默设置 BoolSetting，不触发 onChanged 回调，避免循环调用。
     * 注意：Meteor Client 的 Setting#set() 本身会触发回调，
     * 此处通过先判断当前值来减少无效触发。
     * 若需要彻底阻断回调，可改用反射访问内部字段（视源码版本而定）。
     */
    private void silentSet(Setting<Boolean> setting, boolean value) {
        if (setting.get() != value) {
            setting.set(value);
        }
    }

    /**
     * 将方块列表拼接为显示字符串，最多显示前 3 个
     */
    private String blockListNames(List<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(blocks.size(), 3);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append("§f、§b");
            sb.append(blocks.get(i).getName().getString());
        }
        if (blocks.size() > 3) sb.append("§f 等 §b").append(blocks.size()).append("§f 种");
        return sb.toString();
    }

    // ============================================================
    //  模块启用 / 禁用
    // ============================================================

    @Override
    public void onActivate() {
        ChatUtils.info("§a[Baritone助手] §f模块已启用。");
    }

    @Override
    public void onDeactivate() {
        cancelAll();
        ChatUtils.info("§e[Baritone助手] §f模块已禁用，Baritone 任务已全部取消。");
    }
}
