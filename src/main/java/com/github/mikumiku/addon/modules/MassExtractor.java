package com.github.mikumiku.addon.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import com.github.mikumiku.addon.BaseModule;
import com.github.mikumiku.addon.util.ScanData;
import com.github.mikumiku.addon.util.Via;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Predicate;

/**
 * Foreman MassExtractor — AFK bulk single-block miner for anarchy servers (Minecraft 1.21.4).
 * <p>
 * Design goals:
 * - Never lock up. A flat finite-state machine drives everything; any unexpected
 * condition transitions to a safe PAUSED state instead of stalling.
 * - Mining is STOCK Baritone's BuilderProcess.clearArea quarry, run one small sub-box at a
 * time (each chunk is split into clear-box-size cells so the bot repositions often and walks
 * back over its drops to collect them) and walked outward in a spiral. clearArea is box-confined
 * and breaks the nearest
 * block first, so it can't wander to a distant target and skips the per-tick visibility
 * raytrace that overwhelms MineProcess on abundant blocks (deepslate/stone). No patched
 * Baritone fork is required — any Baritone (incl. Meteor's bundled one) works.
 * - When the inventory fills, run a storage cycle: place ender chest, pull an
 * empty shulker, fill it, recover it, store it back, restock the pickaxe, then
 * recover the ender chest and resume.
 * <p>
 * DESYNC SAFETY: every packet-generating action (drop, hotbar swap, block place,
 * container open/close, slot shift-move, block break) happens on its OWN tick and is
 * followed by an {@code action-delay} wait. Nothing fires multiple packets in a single
 * tick. This is what keeps the bot from outrunning the server on a high-ping anarchy
 * connection (2b2t etc.), where a burst of same-tick packets desyncs client and server.
 */
public class MassExtractor extends BaseModule {
    /**
     * SLF4J logger — debug output lands in the game's latest.log under "(Foreman)".
     */
    private static final Logger LOG = LoggerFactory.getLogger("采矿");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgArea = settings.createGroup("区域");
    private final SettingGroup sgStorage = settings.createGroup("存储");
    private final SettingGroup sgDeposit = settings.createGroup("物资存放箱");
    private final SettingGroup sgTools = settings.createGroup("工具");
    private final SettingGroup sgSafety = settings.createGroup("安全");
    private final SettingGroup sgCave = settings.createGroup("洞穴处理");
    private final SettingGroup sgRender = settings.createGroup("渲染");

    // ---------------- Settings ----------------

    private final Setting<List<Block>> mineBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("目标方块")
        .description("你正在开采的目标方块——用于决定补充哪种工具（深层石/石头 → 镐，沙子/沙砾 → 铲子），以及用于调试日志中“选区内剩余目标”的计数。注意：无论如何，ClearArea（清理区域）模式会破坏选区内的所有方块；你真正保留的物品是由“保留物品（keep-items）”设置决定的，其他所有物资都会被当作垃圾丢弃。因此这只是工具提示，而不是挖矿过滤器。")
        .defaultValue(List.of(Blocks.DEEPSLATE, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE))
        .build()
    );

    private final Setting<List<Item>> keepItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("保留物品")
        .description("真正需要保留、存入潜影盒并计入“背包已满”状态的掉落物——即挖矿的实际产出。不使用精准采集挖深层石会掉落深板岩碎石，所以它是默认项；同时也默认保留深层石，以防你使用了精准采集。任何不在此列表中的物品（如挖掘隧道时产生的泥土/石头）都将被视为垃圾。")
        .defaultValue(List.of(Items.COBBLED_DEEPSLATE, Items.DEEPSLATE, Items.TOTEM_OF_UNDYING,
            Items.FIREWORK_ROCKET, Items.ENDER_CHEST, Items.ENDER_PEARL, Items.END_CRYSTAL))
        .build()
    );
    private final Setting<List<Item>> keepInventoryItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("背包保留物品")
        .description("需要保留“背包”的物品。")
        .defaultValue(List.of(Items.TOTEM_OF_UNDYING,
            Items.FIREWORK_ROCKET, Items.ENDER_CHEST, Items.ENDER_PEARL, Items.END_CRYSTAL))
        .build()
    );

    private final Setting<Integer> minYLevel = sgGeneral.add(new IntSetting.Builder()
        .name("最低 Y 高度")
        .description("绝对底线。Baritone 绝不会挖掘低于此 Y 坐标的地方，末影箱和潜影盒也绝不会放置在该高度以下——这样机器人就不会挖进基岩层并把自己卡死。请将其保持在基岩层上方（在 1.21.4 中约为 -59）。修改此项后请重新启用本模块，以便将新设置推送到 Baritone。")
        .defaultValue(-59)
        .min(-64).max(320)
        .sliderRange(-64, 120)
        .build()
    );

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("动作延迟")
        .description("在执行每一个独立动作（如丢弃、切换快捷栏、放置方块、打开/关闭容器、移动单格物品、破坏方块）后的等待刻数（Ticks）。每个动作都独立计算——绝不会产生瞬时动作爆发。数值越高 = 速度越慢，但在高延迟或卡顿的服务器（如 2b2t）上能极其有效地防止客户端与服务端脱机（Desync）。20 刻 = 1 秒。")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 60)
        .build()
    );

    private final Setting<Integer> delayJitter = sgGeneral.add(new IntSetting.Builder()
        .name("延迟抖动")
        .description("在每次动作等待中随机增加（最高达到此百分比）的延迟时间，使机器人的操作不像节拍器一样绝对规律。它只会增加等待时间（绝不会比设定的“动作延迟”快），因此不会导致脱机——纯粹是为了避免被反作弊的定时检测标记。0 = 关闭。")
        .defaultValue(40)
        .min(0).sliderRange(0, 150)
        .build()
    );

    private final Setting<Integer> clearBoxSize = sgGeneral.add(new IntSetting.Builder()
        .name("清理区块大小")
        .description("将每个区块划分为指定宽度（N×N 底面积，全高度）的子区块进行清理，而不是一次性大面积清理整个 16×16 的区块。清理完每个子区块后，机器人会重新定位到下一个，因此它会走回刚刚挖过的地面并捡起掉落物——这完美解决了机器人在原地大范围挖矿时导致物品超时刷新的问题。数值越小 = 收集越彻底，但速度越慢（需要更频繁地走动）。16 = 旧版逻辑（一次清空整个区块）；8 ≈ 每个区块分 4 份；4 ≈ 分 16 份。按区块生效，修改后会在进入下一个区块时应用。")
        .defaultValue(8)
        .min(2).max(16).sliderRange(2, 16)
        .build()
    );

    private final Setting<Double> miningReach = sgGeneral.add(new DoubleSetting.Builder()
        .name("挖掘触及范围")
        .description("Baritone 破坏方块时的最远触及范围——即它的 'blockReachDistance'，默认为 4.5。调低此值会让机器人在挖掘前站得离每个方块更近，这样当方块掉落时，机器人已经处于约 1 格的自动拾取范围内。由于物品拾取现在也由子区块完成后的“吸尘阶段”负责，此项不必再设置得过于激进。底线值为 2.7：低于该值会导致客户端十字准星的射线追踪经常错过较薄或偏移的方块（如栅栏、墙、玻璃板），从而无法触发挖掘，导致机器人每刻疯狂重发“开始破坏”指令并卡死区块。（无论如何，手动辅助破坏可以恢复此类卡死）。约 3.0 的收集效果就很理想。仅在模块激活时推送，关闭时恢复；修改后请重新启用模块。")
        .defaultValue(3.0)
        .min(2.7).max(4.5).sliderRange(2.7, 4.5)
        .build()
    );

    private final Setting<Boolean> collectDrops = sgGeneral.add(new BoolSetting.Builder()
        .name("收集掉落物")
        .description("在清理完每个子区块后，执行一次“吸尘”清扫：让机器人走向该区域内地上残留的每个目标物品（即在“保留物品”列表中的方块）并将其捡起，然后再前往下一个子区块——确保没有物资被刷新掉。无法及时够到的物品会被果断跳过，且整个清扫过程有时间上限，因此绝不会卡死。这是解决“战利品散落一地”最有效的机制。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> collectMaxSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("最大收集秒数")
        .description("每个子区块执行“吸尘清扫”的时间上限。如果达到此时间后机器人仍在追逐掉落物（某些物品可能掉在了无法到达的地方），它将放弃剩余的物品并继续前进，防止少数卡住的物品拖死整个挖矿进程。如果你的子区块设得很大且总是遗留物资，请调高此值；如果你更想注重效率不想过多逗留，请调低此值。")
        .defaultValue(20)
        .min(2).max(120).sliderRange(5, 60)
        .visible(collectDrops::get)
        .build()
    );

    private final Setting<Boolean> verifyClears = sgGeneral.add(new BoolSetting.Builder()
        .name("验证清理结果")
        .description("在一个子区块完成（或卡死）后，扫描是否存在机器人本应清理却遗漏的实体方块——网络卡顿可能会导致 Baritone 误报区块已清空，或者在方块仍存留时便放弃挖掘。如果发现残留，在继续前进之前重新运行该子区块（最多重试 'clear-retries' 次），绝不留下任何残羹冷炙。基岩和机器人脚下的方块会被自动忽略，且有重试次数上限作为兜底，真正无法触及的方块绝不会导致死循环。该操作性能消耗极低，且只对卡顿服务器意义重大；若要无条件信任 Baritone 的首次清理，请关闭此项。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> clearRetries = sgGeneral.add(new IntSetting.Builder()
        .name("清理重试次数")
        .description("发现有残留方块的子区块，在被接受并跳过之前允许重新执行的次数。每次重试都会重新发布 clearArea 指令，让引发故障的网络卡顿有时间平息。数值越高 = 在卡顿服务器上清理得越彻底；受次数上限控制，绝不会导致卡死。")
        .defaultValue(2)
        .min(1).max(10).sliderRange(1, 6)
        .visible(verifyClears::get)
        .build()
    );

    // ----- Area -----

    private final Setting<Boolean> limitArea = sgArea.add(new BoolSetting.Builder()
        .name("限制区域")
        .description("将挖矿范围限制在一个有限区域内，而不是默认的无限向外螺旋推进。当整个预设区域被清空后，机器人会打包手里的物资并结束工作（停止运行，如果开启了“自动断开连接”则会下线）。划定区域也能彻底消除机器人迷路走失的风险。关闭 = 永远向外开采。")
        .defaultValue(false)
        .build()
    );

    private final Setting<AreaMode> areaMode = sgArea.add(new EnumSetting.Builder<AreaMode>()
        .name("区域模式")
        .description("定义有限区域的方式。ChunksFromStart（从起点计算区块）：基于你激活时的区块，根据指定的长度和宽度生成一个区块框——全程无需点击，完全自动化。要从你站立的地方向外挖一个正方形，请将宽长设为一致，并将锚点设为 Corner（角落）。CornerSelect（手动选对角）：右击两个方块以标记对角线（如同 Meteor 自带的 Excavator），带有实时选区渲染——精确到单个方块。")
        .defaultValue(AreaMode.ChunksFromStart)
        .visible(limitArea::get)
        .build()
    );

    private final Setting<AreaAnchor> areaAnchor = sgArea.add(new EnumSetting.Builder<AreaAnchor>()
        .name("区域锚点")
        .description("仅限“从起点计算”模式：选区相对于你激活时所在区块的位置。Center（中心）：你处于选区的正中央（适合“挖空我周围”）。Corner（角落）：你所在的区块作为选区的一角，选区向你面朝的方向延伸——即站在一个角落，看着目标区域，它就会挖空你面前的矩形地块。修改后请重新启用模块。")
        .defaultValue(AreaAnchor.Center)
        .visible(() -> limitArea.get() && areaMode.get() == AreaMode.ChunksFromStart)
        .build()
    );

    private final Setting<Integer> areaWidthChunks = sgArea.add(new IntSetting.Builder()
        .name("区域宽度 - 区块")
        .description("仅限“从起点计算”模式：沿 X 轴的区域大小，以区块为单位（1 区块 = 16 方块）。将其与长度设为相等即可得到正方形。基于你选择的锚点进行放置。修改后请重新启用模块。")
        .defaultValue(3)
        .min(1).max(32).sliderRange(1, 16)
        .visible(() -> limitArea.get() && areaMode.get() == AreaMode.ChunksFromStart)
        .build()
    );

    private final Setting<Integer> areaLengthChunks = sgArea.add(new IntSetting.Builder()
        .name("区域长度 - 区块")
        .description("仅限“从起点计算”模式：沿 Z 轴的区域大小，以区块为单位（1 区块 = 16 方块）。将其与宽度设为相等即可得到正方形。基于你选择的锚点进行放置。修改后请重新启用模块。")
        .defaultValue(3)
        .min(1).max(32).sliderRange(1, 16)
        .visible(() -> limitArea.get() && areaMode.get() == AreaMode.ChunksFromStart)
        .build()
    );

    private final Setting<Integer> areaLayerHeight = sgArea.add(new IntSetting.Builder()
        .name("分层高度")
        .description("仅限限制区域模式。以指定的方块高度为一层，自上而下逐层挖掘区域：机器人在降到下一层之前，会先横扫清理整个区域当前高度的切片（从一角到另一角）——从而绝不会出现某一列被挖穿到地底，而其他地方原封不动的情况。1 = 每次在全区域剥离一层方块（表面最平整 + 拾取效率最高，但走路最多）。设为 2 或更高时，在每一层切片内，机器人会就地自上而下挖掘最近的方块柱（你指定的高度），然后再走向下一列，而不是扁平扫荡——全区域横扫次数减少（速度更快），但移动前会深挖对应高度。将其设置为整个 Y 轴差值 (maxY-minY+1) 即可就地一挖到底。无限区域模式无法预先横扫无限的顶部，因此始终执行单区块全高清理。")
        .defaultValue(1)
        .min(1).max(64).sliderRange(1, 32)
        .visible(limitArea::get)
        .build()
    );

    private final Setting<Keybind> selectionBind = sgArea.add(new KeybindSetting.Builder()
        .name("选区按键")
        .description("仅限“手动选对角”模式：用于标记两个角落的按键。启用模块后，指向一个方块并按一次设定起点角，再按一次设定终点角；设定完毕后立即开始挖掘。默认为鼠标右键。")
        .defaultValue(Keybind.fromButton(GLFW.GLFW_MOUSE_BUTTON_RIGHT))
        .visible(() -> limitArea.get() && areaMode.get() == AreaMode.CornerSelect)
        .build()
    );

    private final Setting<Boolean> requireFullStacks = sgStorage.add(new BoolSetting.Builder()
        .name("要求满堆叠")
        .description("只有在主背包完全塞满时才开始存储循环——即每个目标物品都堆叠至 64，且没有多余的空位装新方块——确保潜影盒总是装满整组物资。占用槽位的保留物品（末影箱、备用盒子、工具、食物）不会阻塞此进程：触发条件纯粹是“无法再容纳哪怕一个目标方块”。关闭此项将退回宽松的“存储前预留空位”模式（可能会以非满组状态打包）。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> freeSlotsBeforeStore = sgStorage.add(new IntSetting.Builder()
        .name("存储前预留空位")
        .description("仅在关闭“要求满堆叠”时生效。当主背包（快捷栏上方的 27 个槽位）最多只剩这么多空位时，启动存储循环。此项计算的是空闲空间，而非占用空间——因此备用的末影箱、工具和食物不再会导致提前触发。快捷栏被完全排除在外（它专门预留为操作缓冲区）。1 = 留出一个槽位的余量；0 = 主背包全满后再存储。")
        .defaultValue(1)
        .min(0).max(26).sliderRange(0, 10)
        .visible(() -> !requireFullStacks.get())
        .build()
    );

    private final Setting<Boolean> dropJunk = sgStorage.add(new BoolSetting.Builder()
        .name("丢弃垃圾")
        .description("将不属于目标方块、工具、优质食物、末影箱或潜影盒的物品统统扔掉。每个“动作延迟”周期扔一件，防止爆发性丢物触发反作弊。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> dropBadFood = sgStorage.add(new BoolSetting.Builder()
        .name("丢弃劣质食物")
        .description("同样将“有风险”的食物当垃圾扔掉：腐肉、蜘蛛眼、有毒/生的马铃薯与生鸡肉、河豚、紫颂果（带负面或传送效果），以及所有炖菜（蘑菇/兔子/甜菜/谜之炖菜——任何不可堆叠的食物）。正常食物（面包、熟肉、胡萝卜、金苹果等）会被保留供 AutoEat 使用。仅在开启“丢弃垃圾”时生效。")
        .defaultValue(true)
        .visible(dropJunk::get)
        .build()
    );

    private final Setting<Boolean> autoDisconnect = sgStorage.add(new BoolSetting.Builder()
        .name("自动断开连接")
        .description("当运行结束时——存储耗尽（没有空潜影盒可用，或末影箱本身已满）或有限的“限制区域”已彻底清空——自动从服务器下线。在上述情况下模块本身也会停止；此选项则是额外将你踢出游戏——非常适合长时间挂机，物资打包妥当后事了拂衣去。关闭 = 仅停止工作并保持在线。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> fillDelay = sgStorage.add(new IntSetting.Builder()
        .name("填充延迟")
        .description("向潜影盒内存放每组物品，以及储存装满的潜影盒之间的刻数（Ticks）间隔。容器槽位转移属于事务性操作，风险远低于世界方块/移动操作，因此该速度快于普通的“动作延迟”，以加速倾倒物资。仍然严格遵守每刻最多移动一次——绝无瞬发现象。延迟抖动会叠加在其上。")
        .defaultValue(1)
        .min(0).sliderRange(0, 20)
        .build()
    );

    // ----- Deposit chests (optional) -----
    // All OFF by default: with deposit-target = EnderChest the whole storage cycle is exactly the
    // original (fill shulkers into the ender chest you carry, stop when it's full). Set a chest mode to
    // also send filled shulkers to fixed double chests at your base and (optionally) restock empties
    // there for near-unlimited runs.

    private final Setting<DepositTarget> depositTarget = sgDeposit.add(new EnumSetting.Builder<DepositTarget>()
        .name("物资存放目标")
        .description("满载潜影盒的最终归宿。EnderChest（末影箱，默认）：将满潜影盒存入你携带的末影箱，塞满即止（适合“只想随便挖几箱”）。DepositChests（存放箱）：末影箱将化身野外缓冲区——从中取出空盒并将满盒存回，逻辑不变；但一旦空盒子耗尽，机器人会将满盒运送至最近标记的“存放箱”倾倒，再从独立的补给箱中提取空盒，随后重返矿场——实现将物资成吨运回基地的近乎无限循环。挖矿时，满潜影盒绝不会占用你的随身背包。EnderChestThenChests：同 DepositChests（为兼容老版本保留）。")
        .defaultValue(DepositTarget.EnderChest)
        .build()
    );

    private final Setting<List<String>> depositChests = sgDeposit.add(new StringListSetting.Builder()
        .name("物资存放箱列表")
        .description("满载潜影盒的卸货坐标——格式为 \"x y z\"（每行一个）。可以在游戏中准星对准箱子按下绑定的标记键添加，也可手动输入。机器人会倾倒进最近的箱子，若已满或被遮挡则顺延至下一个。数据跨运行保存——一次建好基地，终身受用。")
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest)
        .build()
    );

    private final Setting<Keybind> markDepositBind = sgDeposit.add(new KeybindSetting.Builder()
        .name("标记存放箱按键")
        .description("准星瞄准一个箱子并按下此键，将其坐标加入“物资存放箱”（卸货点）列表。在模块开启期间随时可用；自动忽略重复添加。默认按键：K。")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_K))
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest)
        .build()
    );

    // (No "deposit after N shulkers" setting: the ender chest is the field buffer, so a trip happens once
    // it runs out of empty shulkers — i.e. the batch size is however many empty shulkers you keep stocked
    // in it / refill per trip. See 'empties-per-trip'.)

    private final Setting<Boolean> refillEmpties = sgDeposit.add(new BoolSetting.Builder()
        .name("补充空潜影盒")
        .description("在存放行程中，卸下满载潜影盒后，同样造访“补给箱”并提取一批全新的空潜影盒，让机器人能继续打包。这就是让运行趋于无限的灵魂所在。关闭 = 仅卸货，当你出发时带的空潜影盒耗尽时挂机结束。")
        .defaultValue(true)
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest)
        .build()
    );

    private final Setting<List<String>> supplyChests = sgDeposit.add(new StringListSetting.Builder()
        .name("补给箱列表")
        .description("提取空潜影盒的地方——必须与存放箱独立的箱子，坐标格式为 \"x y z\"。请在这些箱子里屯满备用的空潜影盒。在行程中，机器人会在最近的存放箱卸货，然后走向最近的补给箱提取空盒。使用相应的按键标记它们。仅在开启“补充空潜影盒”时生效。")
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest && refillEmpties.get())
        .build()
    );

    private final Setting<Keybind> markSupplyBind = sgDeposit.add(new KeybindSetting.Builder()
        .name("标记补给箱按键")
        .description("准星瞄准一个箱子并按下此键，将其坐标加入“补给箱”（拿空盒子的地方）列表。在模块开启期间随时可用；自动忽略重复添加。默认按键：L。")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_L))
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest && refillEmpties.get())
        .build()
    );

    private final Setting<Integer> emptiesPerTrip = sgDeposit.add(new IntSetting.Builder()
        .name("单次行程补盒数")
        .description("每次存放行程中，从补给箱抓取的空潜影盒数量（当开启补充时）。空潜影盒不可堆叠，因此每个都会占用箱子和背包的一个格子。")
        .defaultValue(6)
        .min(1).max(27).sliderRange(1, 18)
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest && refillEmpties.get())
        .build()
    );

    private final Setting<Integer> maxDepositDistance = sgDeposit.add(new IntSetting.Builder()
        .name("最大存放距离")
        .description("禁止机器人走向超过此方块数（直线距离）的箱子。如果所有标记的箱子都超过该距离，机器人会选择原地挂起，而不是跋山涉水穿越整张地图——螺旋挖矿会不断将工作面推离基地，所以这是限制往返路程的安全阀。0 = 无限制。")
        .defaultValue(1024)
        .min(0).max(10000).sliderRange(0, 4000)
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest)
        .build()
    );

    private final Setting<Boolean> renderChests = sgDeposit.add(new BoolSetting.Builder()
        .name("渲染箱子")
        .description("在每个标记的存放箱和补给箱位置绘制一个方框。")
        .defaultValue(true)
        .visible(() -> depositTarget.get() != DepositTarget.EnderChest)
        .build()
    );

    private final Setting<ToolType> toolType = sgTools.add(new EnumSetting.Builder<ToolType>()
        .name("补充工具类型")
        .description("当手头工具耐久告急时，从末影箱拿取哪种工具。Auto（自动）会根据你要挖的方块机智推导（沙子/沙砾 → 铲子，深层石/石头 → 镐，原木 → 斧头）。")
        .defaultValue(ToolType.Auto)
        .build()
    );

    private final Setting<Boolean> alsoRestockShovel = sgTools.add(new BoolSetting.Builder()
        .name("附带补充铲子")
        .description("除了主工具外，手里常备一把全新的铲子。这样在采石场遇到沙砾/沙子时，会使用趁手的铲子瞬间挖掉，而不是用镐子痛苦煎熬。在存储循环期间只要末影箱开启就会顺手补货——只需在末影箱里散放几把备用铲子即可。尽力而为机制：即使没有铲子也绝不会暂停运行（与主工具不同）。关闭，或主工具本就是铲子时，此项不作为。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> restockDurability = sgTools.add(new IntSetting.Builder()
        .name("最小工具耐久度")
        .description("当工具剩余耐久低于此数值时，立即从末影箱换一把新的。")
        .defaultValue(60)
        .min(1).sliderRange(1, 200)
        .build()
    );

    private final Setting<Boolean> reserveSilk = sgTools.add(new BoolSetting.Builder()
        .name("保留精准采集")
        .description("绝不提取带有精准采集的工具，将你的精准采集镐死死留给末影箱（让 Meteor 的 AutoTool 的 'silk-touch-for-ender-chest' 去用它）。如果你使用时运或普通附魔挖矿请保持开启；如果你正真切地用精准采集下矿，请务必关闭。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> restockFromShulker = sgTools.add(new BoolSetting.Builder()
        .name("从潜影盒补充工具")
        .description("当背包里没有新工具时，从存放于末影箱内的“工具专用潜影盒”里掏一把出来：放置末影箱 → 拿出工具盒 → 放置并打开 → 抓取新工具 → 敲掉工具盒放回（带着剩下的工具） → 收回末影箱。这让末影箱区区一格就能塞满一整盒备用工具，专为极限长时间挂机打造。工具盒通过其内部物品（装着全新“补充工具”）自动识别，无需特殊命名或染色，绝不碰你的战利品盒子。关闭 = 仅在存储循环时抓取散装工具（这要求你在末影箱里散放备用工具）。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> stopToolFight = sgTools.add(new BoolSetting.Builder()
        .name("叫停工具拔河")
        .description("将工具选择权让渡给 Meteor 的 AutoTool，彻底终结快捷栏的“工具拔河”。当 Baritone 和 Meteor 同时运行自动工具时，它们会为快捷栏大打出手（每秒切换约 30 次），这将打断所有的方块破坏动作，使整个区块冻结。开启后：如果你启用了 Meteor 的 AutoTool，插件会在启动时直接禁用 Baritone 的自动工具，防患于未然；作为后手，如果在运行中检测到槽位疯狂跳动，也会强制介入禁用。关闭模块时恢复。如果你不用 Meteor AutoTool 则一切照旧。无论如何，Meteor 的 AutoTool 在选工具上都更胜一筹。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnHazard = sgSafety.add(new BoolSetting.Builder()
        .name("遇险暂停")
        .description("若身旁惊现岩浆，或有非友好玩家逼近，立刻悬崖勒马暂停作业。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> hazardPlayerRange = sgSafety.add(new DoubleSetting.Builder()
        .name("玩家靠近暂停范围")
        .description("如果有其他玩家进入此方块距离以内，立刻暂停。")
        .defaultValue(48)
        .min(0).sliderRange(0, 128)
        .visible(pauseOnHazard::get)
        .build()
    );

    private final Setting<Boolean> pauseToEat = sgSafety.add(new BoolSetting.Builder()
        .name("暂停以进食")
        .description("饥饿度低下时暂停挖矿，以便 AutoEat（或你本人）能安心把饭吃完。Baritone 采石场会强制鼠标按住镐子进行挖掘，这会打断咀嚼动作——没了这个设置，机器人就会在吃和挖之间无限死循环，直到饿死。暂停期间释放采石场控制权；吃饱喝足后继续开干。如果你身上没吃的，此项不生效（毕竟暂停了你也变不出食物）。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> eatBelowHunger = sgSafety.add(new IntSetting.Builder()
        .name("饥饿度低于此值进食")
        .description("当饥饿度降至该点数或更低时暂停（20 = 满）。将此值设置在等于或高于你 AutoEat 的触发阈值，这样一旦 AutoEat 想开吃，采石场就能立刻让步（如果我们设得低，在缝隙期依然会死循环）。满血复活（20）后继续挖矿。")
        .defaultValue(18)
        .min(1).max(19).sliderRange(1, 19)
        .visible(pauseToEat::get)
        .build()
    );

    private final Setting<Boolean> restockFood = sgSafety.add(new BoolSetting.Builder()
        .name("从潜影盒补充食物")
        .description("弹尽粮绝时，撬开末影箱里的“食物专用潜影盒”饱餐一顿——这是一套独立于战利品和工具补充的独立循环。放末影箱 → 取食物盒 → 放置并打开 → 抓取食物（优先级：金胡萝卜 → 附魔金苹果 → 金苹果 → 熟肉/面包 → 其他可食用物） → 敲掉放回 → 收回末影箱。只要手头优质食物降至“最小保留食物量”以下就立刻触发（1 = 吃掉最后一口时），触发条件纯看食物库存，而非饥饿度，确保你在饿肚子前就备好新粮。同样通过内容物识别，绝不碰战利品盒子。所有动作带有与主程序一致的重试验证机制，不怕卡顿丢失盒子。尽力而为机制：如果没找到食物盒，发个警告继续挖，绝不原地罢工。请在末影箱里备好一整盒食物。关闭 = 饿死事小，靠出门带的干粮死撑。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> minFoodOnHand = sgSafety.add(new IntSetting.Builder()
        .name("最小保留食物量")
        .description("当背包中优质（无风险）食物数量低于此值时，立刻呼叫空投（补充食物）。1（默认）= 吃掉最后一份食物的瞬间进行补充。更高的值能为你保留更大的生命缓冲。触发条件完全基于食物数量，因此补充会在饥饿暂停生效之前未雨绸缪地发生。")
        .defaultValue(1)
        .min(1).max(64).sliderRange(1, 32)
        .visible(restockFood::get)
        .build()
    );

    private final Setting<Integer> foodRestockCount = sgSafety.add(new IntSetting.Builder()
        .name("食物补充数量")
        .description("每次补给从食物盒里掏出多少物资——机器人会补足到这么多个优质食物（按优先级提取整组）。64 = 满满一组（例如金胡萝卜），足以支撑漫长的岁月。强制向上取值至少等于“最小保留食物量”，决不会出现补完还低于触发线这种蠢事。")
        .defaultValue(64)
        .min(1).max(1024).sliderRange(16, 256)
        .visible(restockFood::get)
        .build()
    );

    private final Setting<Integer> pauseRetryTicks = sgSafety.add(new IntSetting.Builder()
        .name("暂停重试刻数")
        .description("在因遇险或缺少资源而暂停后，等待多久再次探头检查是否安全。")
        .defaultValue(40)
        .min(5).sliderRange(5, 200)
        .build()
    );

    private final Setting<Boolean> debugLog = sgSafety.add(new BoolSetting.Builder()
        .name("调试日志")
        .description("将机器人的行为轨迹（状态变更、区块推进及剩余目标计数、存储步骤、暂停、推给 Baritone 的参数）带时间戳地记录在游戏的 latest.log 中，前缀为 '(Foreman)'。日常挂机请关闭；测试捉虫时可开启以截取可分享的日志，查完关掉。日志会在同一文件里与 Baritone 的寻路数据交相辉映。")
        .defaultValue(false)
        .build()
    );

    // ----- Cave handling -----
    // Caves at deepslate depth leave the box full of air pockets/caverns. clearArea skips the air
    // (nothing to break) but Baritone's conservative fall defaults make it REFUSE to drop down to the
    // solid blocks around a cavern, so it detours forever or gives up (and our 30s stall-skip then
    // abandons the chunk with blocks left). With good armour + totems you can safely relax those
    // limits. These are pushed to STOCK Baritone while the module is active and RESTORED on deactivate,
    // so they never leak into your manual Baritone use. Lava is NEVER walked/fallen into (lava landings
    // stay cost-infinity and 'assume-walk-on-lava' is forced off), and falling-block (sand/gravel)
    // cascade avoidance is left at Baritone's safe default.

    private final Setting<Boolean> caveHandling = sgCave.add(new BoolSetting.Builder()
        .name("洞穴处理")
        .description("解开 Baritone 的移动枷锁，让它敢于纵身跳入洞穴去开采周遭的方块，而不是面对虚空绕路甚至直接抛弃该区块。仅在模块激活时注入，关闭后乖乖恢复。深板岩层千疮百孔，建议常开；若想信奉 Baritone 那套绝对安全的移动哲学，请关闭。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxFallHeight = sgCave.add(new IntSetting.Builder()
        .name("最大坠落高度")
        .description("Baritone 在没有水桶的情况下，愿意纵身跃上坚实地面的最大落差（即 Baritone 的 'maxFallHeightNoWater'，原版默认值为 3，意为无伤极限）。拉高能让机器人大胆跳下矿洞深井。它绝不会往岩浆里跳（岩浆落点成本无限高）。20 ≈ 有好甲在身就能扛下的落差；255 = 信仰之跃（纯靠不死图腾硬扛）。")
        .defaultValue(20)
        .min(3).max(255).sliderRange(3, 64)
        .visible(caveHandling::get)
        .build()
    );

    private final Setting<Boolean> allowParkourPlace = sgCave.add(new BoolSetting.Builder()
        .name("允许跑酷垫方块")
        .description("允许 Baritone 在半空中凌空垫置方块以制造跑酷落脚点。极其可靠；能有效横跨洞穴岩架间的小裂谷。平衡模式下常开。")
        .defaultValue(true)
        .visible(caveHandling::get)
        .build()
    );

    private final Setting<Boolean> allowDiagonalDescend = sgCave.add(new BoolSetting.Builder()
        .name("允许对角线下降")
        .description("允许 Baritone 呈对角线向下踩步。略带风险（可能蹭到未经安全检查的相连方块），但能显著加快下矿洞的速度。平衡模式下常开。")
        .defaultValue(true)
        .visible(caveHandling::get)
        .build()
    );

    private final Setting<Boolean> allowParkour = sgCave.add(new BoolSetting.Builder()
        .name("允许跑酷")
        .description("允许 Baritone 助跑起跳越过裂谷。不如垫方块可靠，且容易跳过头，因此默认打入冷宫——如果你追求极具侵略性的洞穴狂飙，请开启。")
        .defaultValue(false)
        .visible(caveHandling::get)
        .build()
    );

    private final Setting<Boolean> allowDiagonalAscend = sgCave.add(new BoolSetting.Builder()
        .name("允许对角线上升")
        .description("允许 Baritone 呈对角线向上跨步攀爬。平衡模式下默认关闭；如果你追求极具侵略性的寻路，请将其开启。")
        .defaultValue(false)
        .visible(caveHandling::get)
        .build()
    );

    // ----- Rendering -----

    private final Setting<Boolean> renderArea = sgRender.add(new BoolSetting.Builder()
        .name("渲染选区")
        .description("在世界中绘制出有限挖掘区域的边界框（同时，在“手动选对角”模式下，会高亮显示你准星瞄准的方块以及正在拉扯生成的选区预览框）。仅在开启“限制区域”时生效。")
        .defaultValue(true)
        .visible(limitArea::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("渲染样式")
        .description("选区框在游戏内的显示方式：实心面填充（Sides）、纯线条描边（Lines），或是双管齐下（Both）。")
        .defaultValue(ShapeMode.Both)
        .visible(() -> limitArea.get() && renderArea.get())
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("表面填充颜色")
        .description("选区框各个侧面的实心填充颜色。")
        .defaultValue(new SettingColor(0, 200, 255, 30))
        .visible(() -> limitArea.get() && renderArea.get())
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("轮廓线条颜色")
        .description("选区框边缘轮廓线的颜色。")
        .defaultValue(new SettingColor(0, 200, 255, 255))
        .visible(() -> limitArea.get() && renderArea.get())
        .build()
    );

    // ---------------- State ----------------

    private enum State {
        SELECT,            // CornerSelect mode: waiting for the player to mark two corners
        MINING,            // Baritone is mining target blocks
        COLLECT,           // vacuum pass: walk over the just-cleared sub-box's drops before the next box
        UNSTICK,           // manual-break assist: hold a break on a block Baritone stalled on, then resume
        CLEAR_AREA,        // mine a small pocket so the echest + shulker both have room
        PLACE_ECHEST,      // place the ender chest (multi-step)
        ECHEST_TAKE,       // open echest, restock pickaxe, pull an empty shulker (multi-step)
        PLACE_SHULKER,     // place the empty shulker (multi-step)
        SHULKER_FILL,      // open shulker, dump target stacks one per tick (multi-step)
        BREAK_SHULKER,     // break the filled shulker to pick it up
        PICKUP_SHULKER,    // chase + collect the broken filled shulker before reopening the echest
        ECHEST_STORE,      // reopen echest, store the filled shulker one per tick (multi-step)
        BREAK_ECHEST,      // break the echest to pick it back up
        RESTOCK,           // tool-shulker restock cycle (driven by an inner RestockPhase FSM)
        FOOD_RESTOCK,      // food-shulker restock cycle (driven by an inner FoodPhase FSM)
        DEPOSIT,           // travel to a marked chest, deposit filled shulkers, refill empties (DepositPhase FSM)
        PAUSED,            // hazard / waiting
        DONE               // ender chest full of filled shulkers
    }

    /**
     * Result of one sub-step of a placement sequence.
     */
    private enum Place {BUSY, DONE, FAILED}

    /**
     * Sub-phases of the tool-shulker restock cycle (the {@link State#RESTOCK} state). Pull a fresh tool
     * from a tool-shulker stored in the ender chest, then put the shulker back: place echest → open →
     * take tool-shulker → close → place shulker → open → take tool → close → break shulker → reopen
     * echest → return shulker → close → break echest → done.
     */
    private enum RestockPhase {
        PLACE_ECHEST,   // place the ender chest
        OPEN_ECHEST,    // open it
        TAKE_SHULKER,   // pull the tool-shulker into the hotbar
        CLOSE_ECHEST,   // close the chest
        PLACE_SHULKER,  // place the tool-shulker
        OPEN_SHULKER,   // open it
        TAKE_TOOL,      // grab a fresh tool into the hotbar
        STOW_TOOL,      // put the worn (not broken) tool(s) back into the shulker
        CLOSE_SHULKER,  // close it
        BREAK_SHULKER,  // break the tool-shulker (drops with its remaining + stowed tools)
        PICKUP_SHULKER, // wait until the dropped tool-shulker is collected
        REOPEN_ECHEST,  // reopen the ender chest
        RETURN_SHULKER, // put the tool-shulker back in the chest
        CLOSE_ECHEST2,  // close the chest
        BREAK_ECHEST,   // break + collect the ender chest
        DONE            // resume mining (or pause, if the cycle aborted)
    }

    /**
     * Sub-phases of the food-shulker restock cycle (the {@link State#FOOD_RESTOCK} state). Mirrors the
     * tool-shulker cycle: crack a food-shulker kept in the ender chest, take food (by preference order),
     * put the shulker back, recover the echest. place echest → open → take food-shulker → close → place
     * shulker → open → take food → close → break shulker → reopen echest → return shulker → close → break
     * echest → done. Every world/container action is one packet on its own tick + action-delay, and the
     * break/pickup uses the shared settle → chase → confirm tracker, exactly like the tool cycle.
     */
    private enum FoodPhase {
        PLACE_ECHEST,   // place the ender chest
        OPEN_ECHEST,    // open it
        TAKE_SHULKER,   // pull the food-shulker into the hotbar
        CLOSE_ECHEST,   // close the chest
        PLACE_SHULKER,  // place the food-shulker
        OPEN_SHULKER,   // open it
        TAKE_FOOD,      // grab food (by preference order) up to the restock count
        CLOSE_SHULKER,  // close it
        BREAK_SHULKER,  // break the food-shulker (drops with its remaining food)
        PICKUP_SHULKER, // wait until the dropped food-shulker is collected
        REOPEN_ECHEST,  // reopen the ender chest
        RETURN_SHULKER, // put the food-shulker back in the chest
        CLOSE_ECHEST2,  // close the chest
        BREAK_ECHEST,   // break + collect the ender chest
        DONE            // resume mining
    }

    /**
     * Which tool the restock pulls. Auto = derive from the target blocks' mineable tag.
     */
    public enum ToolType {Auto, Pickaxe, Shovel, Axe, Hoe}

    /**
     * How a finite mining area is defined when {@code limit-area} is on.
     */
    public enum AreaMode {ChunksFromStart, CornerSelect}

    /**
     * Where the ChunksFromStart box sits relative to the activation chunk.
     */
    public enum AreaAnchor {Center, Corner}

    /**
     * Where filled shulkers are sent. See {@code deposit-target}.
     */
    public enum DepositTarget {EnderChest, DepositChests, EnderChestThenChests}

    /**
     * Sub-phases of a deposit trip (the {@link State#DEPOSIT} state). The filled shulkers live in the
     * ender chest (the field buffer) and STAY there until the bot is standing at the deposit chest — so a
     * death on the way to base never strands the haul (it's in the global ender chest, not the bot's
     * inventory). Order: walk to the nearest deposit chest with a clean inventory → at the chest, EXTRACT
     * the filled loot shulkers out of the echest (place echest → open → pull → break) → dump them into the
     * deposit chest → (if refilling) walk to the nearest, separate, supply chest and take ONLY as many
     * empties as will fit in the echest → STOCK those empties straight into the echest → back to mining.
     */
    private enum DepositPhase {
        PATH_TO_DEPOSIT,                                             // walk to the deposit chest (inv clean; filled stay safe in the echest)
        PULL_PLACE, PULL_OPEN, PULL_FILLED, PULL_CLOSE, PULL_BREAK,  // at the chest: extract the filled loot shulkers out of the echest
        OPEN_DEPOSIT, DEPOSIT_FILLED, CLOSE_DEPOSIT,                 // dump them into the deposit chest
        PATH_TO_SUPPLY, OPEN_SUPPLY, TAKE_EMPTIES, CLOSE_SUPPLY,     // refill empties (only as many as fit in the echest)
        STOCK_PLACE, STOCK_OPEN, STOCK_EMPTIES, STOCK_CLOSE, STOCK_BREAK, // put those empties into the ender chest
        DONE
    }

    private State state = State.MINING;
    private int step = 0;                   // sub-step within the current state
    private int attempts = 0;               // retry/timeout counter within a sub-step
    private int timer = 0;                  // counts down between actions
    private BlockPos placedEchest = null;   // where we placed the ender chest this cycle
    private BlockPos placedShulker = null;  // where we placed the shulker this cycle
    private BlockPos pendingPlace = null;   // chosen spot mid-placement (between sub-steps)
    private String placeFail = "";          // why the last tickPlace returned FAILED (for the pause message)
    private final java.util.Set<BlockPos> placeTried = new java.util.HashSet<>(); // spots a place packet failed on this attempt
    private String doneReason = "storage full"; // why the DONE state was entered (for the message/action)
    private IBaritone baritone;
    private final java.util.Random random = new java.util.Random();
    // Baritone settings we changed this run -> their original values, restored on deactivate.
    private final java.util.Map<String, Object> savedBaritone = new java.util.HashMap<>();

    // Tool-fight auto-heal (see 'stop-tool-fight'): two tool-selectors (Baritone auto-tool vs Meteor
    // AutoTool) can flip the selected hotbar slot every tick, cancelling every break. We sample the selected
    // slot each mining tick; if it changes too many times within a 1s window it's a thrash, and we push
    // Baritone autotool=false ONCE (restored on deactivate) to leave a single selector in charge.
    private int selSlotPrev = -1;
    private int toolSwitchCount = 0;
    private int toolWindowTicks = 0;
    private boolean baritoneAutoToolSuppressed = false;
    private static final int TOOL_FIGHT_SWITCHES = 10;  // >= this many selected-slot changes within the window = a fight
    private static final int TOOL_FIGHT_WINDOW = 20;     // 1s sampling window
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    // Outward chunk spiral (chunk coords). startC* is the spiral centre captured at activation;
    // the spiral stepper walks outward from it, and curC* is the chunk currently being cleared.
    private int startCX, startCZ, curCX, curCZ;
    private int spX, spZ, spDir, spSegLen, spSegLeft, spSegDone; // outward-square spiral stepper
    private int quarryTopY = 0; // quarry ceiling (player's start Y + 1); box top for clearArea

    // ClearArea engine state (stock BuilderProcess quarry).
    private boolean clearAreaStarted = false; // have we issued clearArea for the current chunk yet
    private int clearAreaStallTicks = 0;      // ticks the bot hasn't moved while the builder is active
    private BlockPos lastClearPos = null;     // last player pos, for stall detection
    private static final int CLEARAREA_STALL_TICKS = 20 * 10; // 10s without moving while mining = stuck -> skip box
    private static final int TRAVEL_STALL_TICKS = 20 * 30;    // 30s without moving en route to a base chest = give up
    private boolean wantToEat = false;        // hunger-pause hysteresis: latched once food dips to the eat threshold, cleared when full

    // Reach floor: the lowest blockReachDistance we'll push to Baritone. Below this the client crosshair
    // raytrace keeps just missing thin/offset blocks (fences/walls), so the dig re-STARTs every tick instead
    // of holding to completion and the chunk stalls (captured in a 2b packet log: 10x START_DESTROY_BLOCK,
    // never a STOP). The mining-reach setting's own min matches this.
    private static final float MIN_MINING_REACH = 2.7f;

    // Manual-break assist (State.UNSTICK): when Baritone stalls mid-mining on a block it can't finish breaking
    // (the fence/edge-block START-spam above), the addon takes over and HOLDS a break on the offending block
    // itself — explicit rotation + continuous updateBlockBreakingProgress, exactly like the shulker/echest
    // breaks — which isn't subject to the low client crosshair reach (vanilla interaction range ~4.5). On
    // success it hands the sub-box back to Baritone; only if that can't make progress does it fall back to the
    // original 10s skip. This is the guaranteed-recovery half (the reach floor is the prevent-most half).
    private static final int UNSTICK_STALL_TICKS = 20 * 3;    // 3s of no movement while mining -> try the assist (well before the 10s skip)
    private static final int UNSTICK_BREAK_TICKS = 20 * 6;    // hold the break up to 6s per block before giving up on it
    private static final int UNSTICK_MAX_BLOCKS = 8;         // consecutive assist-breaks without the bot moving, then skip the sub-box
    private static final double UNSTICK_REACH = 4.5;       // vanilla interaction range — independent of the lowered Baritone crosshair reach
    private BlockPos unstickPos = null;       // the block the assist is currently holding a break on
    private int unstickTicks = 0;             // ticks spent breaking the current stuck block
    private int unstickBlocksDone = 0;        // assist breaks since the bot last moved (cap before skipping)
    private int subBoxRetries = 0;            // re-runs of the current sub-box because it verified as not-cleared (cap = clear-retries)

    // Drop-collection (State.COLLECT): after a sub-box clears, walk over its dropped kept-items.
    private int[] collectBounds = null;       // {x1,z1,x2,z2} of the sub-box being vacuumed (snapshot)
    private boolean collectStalled = false;   // carry the sub-box's stalled flag through to afterSubBox
    private ItemEntity collectTarget = null;  // the drop we're currently walking to
    private boolean collectPathed = false;    // have we issued the path to collectTarget yet (false = still letting it settle)
    private int collectSettleTicks = 0;       // ticks waited for the current drop to settle on the ground
    private int collectTargetTicks = 0;       // ticks spent reaching the current drop (per-item give-up watch)
    private int collectTotalTicks = 0;        // ticks spent on this whole sub-box's vacuum (overall cap)
    private final java.util.Set<Integer> collectSkip = new java.util.HashSet<>(); // drops we gave up reaching
    private static final int COLLECT_ITEM_TICKS = 20 * 3;   // 3s to reach one drop, else skip it
    private static final int COLLECT_SETTLE_TICKS = 20;     // wait up to 1s for a fresh drop to settle before chasing it

    // Sub-box subdivision (Option B): clear each chunk in clear-box-size cells so the bot repositions
    // between them and walks back over (and picks up) the drops. subBoxes holds the {x1,z1,x2,z2}
    // cells of the chunk currently being mined; subBoxIdx is the one being cleared right now.
    private final List<int[]> subBoxes = new java.util.ArrayList<>();
    private int subBoxIdx = 0;

    // Bounded area (resolved at activation, or after corner selection). All block coords INCLUSIVE.
    // When areaLimited is false the spiral is infinite and these are unused (legacy behaviour).
    private boolean areaLimited = false;
    private int areaMinX, areaMaxX, areaMinZ, areaMaxZ, areaMinY, areaMaxY;
    private int gridCxMin, gridCxMax, gridCzMin, gridCzMax; // chunk grid covering the box
    private int areaChunksTotal, areaChunksDone;            // progress (per horizontal layer) + layer-complete detection
    private int curLayerTopY = 0;                           // bounded area: top Y of the layer currently being swept (descends top-down)
    private boolean areaComplete = false;                   // every layer down to areaMinY cleared/skipped
    // CornerSelect: the two corners marked via the selection bind (null until set this run).
    private BlockPos corner1 = null, corner2 = null;

    // Tool-shulker restock cycle (State.RESTOCK). 'restocking' routes the shared CLEAR_AREA pocket-prep
    // into the restock FSM instead of a storage cycle; 'restockPhase' is the current sub-phase; and
    // 'restockAbort', if set, makes the cycle pause with that message (after recovering the echest)
    // instead of resuming mining.
    private boolean restocking = false;
    private RestockPhase restockPhase = RestockPhase.PLACE_ECHEST;
    private String restockAbort = null;
    private boolean restockTookFresh = false; // did TAKE_TOOL get a fresh tool (so we may stow the worn one)?
    private int restockShulkersBefore = 0;    // tool-bearing shulkers in inv before the placed one is recovered
    private int restockReturnedBefore = -1;   // RETURN_SHULKER: tool-shulkers in the echest before the store (confirm it lands)
    private int echestStoredBefore = -1;      // ECHEST_STORE: filled shulkers in the echest before the store (confirm it lands)

    // Food-shulker restock cycle (State.FOOD_RESTOCK). 'foodRestocking' routes the shared CLEAR_AREA
    // pocket-prep into the food FSM; 'foodPhase' is the current sub-phase. It's best-effort: if no
    // food-shulker (or no edible food) is found, the cycle still recovers the echest, warns, and latches
    // 'foodRestockExhausted' so it doesn't thrash the echest re-trying — mining just continues (running low
    // on food, unlike a broken tool, never stalls the run; the latch clears on re-activate). 'foodTookAny'
    // records whether any food was actually pulled, 'foodShulkerEmptied' that the shulker was emptied of
    // food (so the now-empty shulker stays recognisable for pickup/return), and 'foodReturnedBefore'
    // confirms the return store landed.
    private boolean foodRestocking = false;
    private FoodPhase foodPhase = FoodPhase.PLACE_ECHEST;
    private String foodRestockAbort = null;
    private boolean foodTookAny = false;
    private boolean foodShulkerEmptied = false;
    private boolean foodRestockExhausted = false;
    private int foodReturnedBefore = -1;

    // Shared shulker-drop pickup (used by the loot-storage cycle AND the tool-restock cycle): after
    // breaking a placed shulker, walk onto its drop and confirm it's back in the inventory before moving
    // on. A broken shulker can bounce a block off the break spot, so we chase the actual ItemEntity —
    // let it settle, then re-path onto it as it drifts — instead of standing on a stale break spot.
    private ItemEntity pickupDrop = null;     // the dropped shulker item we're walking to
    private boolean pickupPathed = false;     // issued the path yet (false = still settling)
    private int pickupSettleTicks = 0;        // ticks waited for the drop to settle on the ground
    private BlockPos pickupGoal = null;       // block we last pathed to (re-path when the drop drifts)
    private int pickupAttempts = 0;           // overall ticks spent waiting (give-up cap)
    private int pickupBefore = 0;             // matching shulkers in inv before the break (success = it rose)
    private boolean pickupSnapshotTaken = false; // a break is in progress: the pre-break count is snapshotted
    private int pickupBreakTicks = 0;         // ticks spent swinging at the block (ghost-block break cap)

    // Deposit-chest trip (State.DEPOSIT). 'depositActive' = the bot uses fixed deposit/supply chests at a
    // base (any deposit-target other than EnderChest). The ender chest stays the FIELD buffer (filled
    // shulkers are stored into it and empties taken from it, exactly like the EnderChest cycle); a trip
    // only happens once the echest runs out of empties, and the trip extracts the filled shulkers back
    // out of the echest to haul them. 'depositPhase' is the trip sub-phase; 'depositChest' the chest
    // currently in use; 'triedChests' the ones skipped this trip (full/unreachable); the travel fields
    // detect a stuck walk-out. 'echestExhausted' marks that the echest has no empty shulkers left.
    private boolean depositActive = false;
    private boolean echestExhausted = false;
    private DepositPhase depositPhase = DepositPhase.PATH_TO_DEPOSIT;
    private BlockPos depositChest = null;
    private final List<BlockPos> triedChests = new java.util.ArrayList<>();
    private int depositTravelTicks = 0;
    private BlockPos lastTravelPos = null;
    private boolean finishAfterDeposit = false; // bounded-area final drop-off: stop once this trip ends
    private boolean tripExtracted = false;      // the filled shulkers have been pulled out of the echest this trip
    private boolean tripRefilled = false;       // this trip stocked at least one empty shulker back into the echest
    private int echestFreeAfterExtract = 0;     // echest free slots after the extract = how many empties may refill

    public MassExtractor() {
        super(CATEGORY_MIKU_BUILD, "挖空置域采矿", "挂机批量挖矿工具。空置域化挖掘、掉落物全收集、挖掘沙砾和沙子时不会被卡住、自动存储物资，装入末影箱内的潜影盒中，自动修复/更换工具");
    }

    // ---------------- Lifecycle ----------------

    @Override
    public void onActivate() {
        dbg("=== activate === mineBlocks=%d keepItems=%d minY=%d actionDelay=%d fillDelay=%d limitArea=%b",
            mineBlocks.get().size(), keepItems.get().size(), minYLevel.get(), actionDelay.get(), fillDelay.get(), limitArea.get());
        baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        applyBaritoneSettings();
        timer = 0;
        placedEchest = null;
        placedShulker = null;
        pendingPlace = null;
        corner1 = null;
        corner2 = null;
        areaComplete = false;
        restocking = false;
        restockPhase = RestockPhase.PLACE_ECHEST;
        restockAbort = null;
        foodRestocking = false;
        foodPhase = FoodPhase.PLACE_ECHEST;
        foodRestockAbort = null;
        foodTookAny = false;
        foodShulkerEmptied = false;
        foodRestockExhausted = false;
        foodReturnedBefore = -1;
        selSlotPrev = -1;
        toolSwitchCount = 0;
        toolWindowTicks = 0;
        baritoneAutoToolSuppressed = false;
        // Proactively defer tool selection to Meteor's AutoTool when it's running, so the Baritone-vs-Meteor
        // hotbar tug-of-war never even starts (no ~1s detect window). detectToolFight() stays as a backstop
        // for AutoTool toggled on AFTER activation. (Pushed after applyBaritoneSettings + the reset above, so
        // it isn't clobbered; snapshotted by pushBaritone so deactivate restores Baritone's auto-tool.)
        if (stopToolFight.get() && meteorAutoToolActive()) {
            pushBaritone(BaritoneAPI.getSettings(), "autotool", false);
            baritoneAutoToolSuppressed = true;
            dbg("tool-fight: Meteor AutoTool active on activate -> proactively pushed baritone autotool=false");
        }
        depositActive = (depositTarget.get() != DepositTarget.EnderChest);
        echestExhausted = false;
        depositPhase = DepositPhase.PATH_TO_DEPOSIT;
        depositChest = null;
        triedChests.clear();
        lastTravelPos = null;
        depositTravelTicks = 0;
        finishAfterDeposit = false;
        tripExtracted = false;
        echestFreeAfterExtract = 0;
        ScanData.clear();   // forget the previous run's scan; the new one runs once the area is resolved

        // CornerSelect: don't mine yet — sit in SELECT until the player marks both corners
        // (handled in selectCorners(), which resolves the box and starts mining). The scan runs there,
        // once the box is known.
        if (limitArea.get() && areaMode.get() == AreaMode.CornerSelect) {
            areaLimited = true;
            go(State.SELECT);
            info("Mark the mining area: aim at a block and press the selection bind for corner 1, then again for corner 2.");
            return;
        }

        resolveArea();   // unbounded spiral, or a ChunksFromStart box centred on the activation chunk
        seedSpiral();
        runChunkScan();  // one-time resource scan of the mining area (after the bounds are resolved)
        go(State.MINING);
        startMining();
    }

    @Override
    public void onDeactivate() {
        dbg("=== deactivate ===");
        stopMining();
        restoreBaritone();   // put any Baritone settings we pushed back to the user's originals
        corner1 = null;
        corner2 = null;
    }

    // ---------------- CornerSelect: Excavator-style two-corner marking ----------------

//    @EventHandler
//    private void onMouseButton(MouseButtonEvent event) {
//        if (event.action == KeyAction.Press) {
//            tryMarkCorner();
//            tryMarkChests();
//        }
//    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action == KeyAction.Press) {
            tryMarkCorner();
            tryMarkChests();
        }
    }

    /**
     * Handle both mark binds: add the aimed-at chest to the deposit or the supply list.
     */
    private void tryMarkChests() {
        if (mc.currentScreen != null || depositTarget.get() == DepositTarget.EnderChest) return;
        if (markDepositBind.get().isPressed()) markChest(depositChests, "deposit");
        if (refillEmpties.get() && markSupplyBind.get().isPressed()) markChest(supplyChests, "supply");
    }

    /**
     * Add the block under the crosshair to the given chest-location list (ignoring duplicates).
     */
    private void markChest(Setting<List<String>> list, String kind) {
        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) return;
        BlockPos pos = bhr.getBlockPos();
        String key = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        var updated = new java.util.ArrayList<>(list.get());
        if (updated.contains(key)) {
            info("That %s chest (%d, %d, %d) is already marked.", kind, pos.getX(), pos.getY(), pos.getZ());
            return;
        }
        updated.add(key);
        list.set(updated);
        info("Marked %s chest %d, %d, %d (%d total).", kind, pos.getX(), pos.getY(), pos.getZ(), updated.size());
    }

    /**
     * Parse a chest-location list ("x y z" strings) into BlockPos, skipping malformed entries.
     */
    private List<BlockPos> parseChests(List<String> raw) {
        List<BlockPos> out = new java.util.ArrayList<>();
        for (String s : raw) {
            String[] p = s.trim().split("\\s+");
            if (p.length != 3) continue;
            try {
                out.add(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private List<BlockPos> depositChestList() {
        return parseChests(depositChests.get());
    }

    private List<BlockPos> supplyChestList() {
        return parseChests(supplyChests.get());
    }

    /**
     * Nearest chest in {@code chests} not already tried this trip, within max-deposit-distance (or null).
     */
    private BlockPos nearestChest(List<BlockPos> chests) {
        BlockPos feet = mc.player.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos c : chests) {
            if (triedChests.contains(c)) continue;
            double d = feet.getSquaredDistance(c);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        if (best != null && maxDepositDistance.get() > 0) {
            double max = maxDepositDistance.get();
            if (bestD > max * max) return null; // every untried chest is out of range
        }
        return best;
    }

    /**
     * While in SELECT, a press of the selection bind marks the block under the crosshair as the next
     * corner. First press sets corner 1, second sets corner 2 and kicks off mining of the resolved box.
     */
    private void tryMarkCorner() {
        if (state != State.SELECT || mc.currentScreen != null) return;
        if (!selectionBind.get().isPressed()) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) return;
        BlockPos pos = bhr.getBlockPos();
        if (corner1 == null) {
            corner1 = pos;
            info("Corner 1 set: %d, %d, %d. Aim at the opposite corner and press again.", pos.getX(), pos.getY(), pos.getZ());
        } else {
            corner2 = pos;
            info("Corner 2 set: %d, %d, %d — mining the area now.", pos.getX(), pos.getY(), pos.getZ());
            resolveAreaFromCorners();
            seedSpiral();
            runChunkScan();  // one-time resource scan of the just-marked area
            go(State.MINING);
            startMining();
        }
    }

    // ---------------- Area box rendering ----------------

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        // Deposit + supply chests render independently of the mining-area box.
        if (renderChests.get() && depositTarget.get() != DepositTarget.EnderChest) {
            for (BlockPos c : depositChestList())
                event.renderer.box(c, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            if (refillEmpties.get())
                for (BlockPos c : supplyChestList())
                    event.renderer.box(c, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
        if (!limitArea.get() || !renderArea.get()) return;
        if (state == State.SELECT) {
            // outline the block under the crosshair, plus corner 1 and the box-in-progress once set
            if (mc.crosshairTarget instanceof BlockHitResult bhr) {
                BlockPos aim = bhr.getBlockPos();
                event.renderer.box(aim, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
                if (corner1 != null) renderBounds(event, corner1, aim);
            }
            if (corner1 != null) event.renderer.box(corner1, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        } else if (areaLimited) {
            // the resolved mining box (block coords are inclusive -> +1 on the max side to cover the blocks)
            event.renderer.box(areaMinX, areaMinY, areaMinZ, areaMaxX + 1, areaMaxY + 1, areaMaxZ + 1,
                sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    /**
     * Render a box spanning two inclusive block corners (covers the full blocks).
     */
    private void renderBounds(Render3DEvent event, BlockPos a, BlockPos b) {
        event.renderer.box(
            Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
            Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1,
            sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    /**
     * Move to a new state, resetting the per-state sub-step + retry counters.
     */
    private void go(State s) {
        if (s != state) dbg("state %s -> %s", state, s);
        state = s;
        step = 0;
        attempts = 0;
    }

    /**
     * The wait after one action: {@code action-delay} plus a random 0..jitter% extra.
     * Always &ge; action-delay, so jitter can never make the bot faster (no desync risk);
     * it only breaks up perfect timing regularity.
     */
    private int nextDelay() {
        return withJitter(actionDelay.get());
    }

    /**
     * Faster cadence for in-container slot transfers (shulker fill / echest store).
     */
    private int fillMoveDelay() {
        return withJitter(fillDelay.get());
    }

    private int withJitter(int base) {
        int pct = delayJitter.get();
        if (pct <= 0) return base;
        int maxExtra = Math.round(base * (pct / 100f));
        return maxExtra > 0 ? base + random.nextInt(maxExtra + 1) : base;
    }

    /**
     * Debug trace to latest.log, only when 'debug-logging' is on.
     */
    private void dbg(String fmt, Object... args) {
        if (debugLog.get()) LOG.info(args.length == 0 ? fmt : String.format(fmt, args));
    }

    /**
     * Count the mine-target blocks still in a chunk box over the quarry's Y band, and how many are
     * exposed (touching air) — for diagnostics. Lets the log show whether a chunk was abandoned
     * with reachable targets still in it (premature advance) vs. genuinely cleared. Capped for cost.
     */
    private int[] countMineBlocksInChunk(int cx, int cz) {
        int bx = cx << 4, bz = cz << 4;
        int x1 = bx, x2 = bx + 15, z1 = bz, z2 = bz + 15;
        int floor = areaMinY, ceil = areaMaxY;
        if (areaLimited) {
            x1 = Math.max(x1, areaMinX);
            x2 = Math.min(x2, areaMaxX);
            z1 = Math.max(z1, areaMinZ);
            z2 = Math.min(z2, areaMaxZ);
        }
        int total = 0, exposed = 0;
        List<Block> blocks = mineBlocks.get();
        for (int y = ceil; y >= floor && total < 4096; y--) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (blocks.contains(mc.world.getBlockState(p).getBlock())) {
                        total++;
                        if (isExposed(p)) exposed++;
                    }
                }
            }
        }
        return new int[]{total, exposed};
    }

    private boolean isExposed(BlockPos p) {
        for (Direction d : Direction.values()) {
            var st = mc.world.getBlockState(p.offset(d));
            if (st.isAir() || st.isReplaceable()) return true;
        }
        return false;
    }

    /**
     * Safe pause: stop, warn, and re-check after a delay (the anti-stall guarantee).
     */
    private void pause(String msg) {
        dbg("PAUSE (from %s): %s", state, msg);
        warning(msg);
        stopMining();
        state = State.PAUSED;
        step = 0;
        attempts = 0;
        timer = pauseRetryTicks.get();
    }

    /**
     * Push the handful of STOCK Baritone settings the ClearArea quarry benefits from (all by name, so
     * the addon stays decoupled from the Baritone jar's mappings). The quarry itself is driven by the
     * box passed to {@code clearArea}, so there are no area/scan settings to configure.
     */
    private void applyBaritoneSettings() {
        Settings s = BaritoneAPI.getSettings();
        savedBaritone.clear();                       // fresh snapshot for this run
        pushBaritone(s, "allowbreak", true);
        // Stand closer to each block (lower reach) so the bot is within the ~1-block pickup range when
        // the block drops -> it collects the haul instead of leaving it to despawn. Baritone stock 4.5.
        // Clamp the pushed reach to a workable floor: below ~2.7 the crosshair raytrace keeps missing thin/
        // offset blocks (fences/walls), so Baritone re-issues START_DESTROY_BLOCK every tick instead of holding
        // one dig to completion, and the chunk stalls. The setting's own min is 2.7; this guards stale configs.
        pushBaritone(s, "blockreachdistance", Math.max(MIN_MINING_REACH, miningReach.get().floatValue()));
        // Cap how long Baritone searches for a path it can't find, so a bedrock-encased / lava-fronted
        // block the builder can't reach fails fast instead of freezing for the default 2s/5s. Quarry
        // targets are always near, so a reachable path is found well under 1s.
        pushBaritone(s, "failuretimeoutms", 1000L);
        pushBaritone(s, "planaheadfailuretimeoutms", 1000L);

        // Mine TOP-DOWN, always. Stock clearArea is nearest-first, which digs to the bottom of the box and
        // works upward. buildInLayers clears one Y-layer at a time; layerOrder=true makes that order top to
        // bottom (BuilderProcess: "if (layerOrder.value) { // top to bottom"). breakFromAbove lets the bot
        // stand on the layer and break the block directly below it (the reach loop starts at dy=-1), which
        // is what makes descending layer-by-layer actually work. All restored on deactivate.
        pushBaritone(s, "buildinlayers", true);
        pushBaritone(s, "layerorder", true);   // true = top to bottom
        // Baritone's own layer thickness. A bounded run already hands clearArea ONE area-slice at a time
        // (layer-height tall), so make that whole slice a SINGLE Baritone layer: within one layer the
        // builder heads to the NEAREST breakable block, so (with breakFromAbove standing on top) it clears
        // the closest TOWER of blocks top-down before stepping to the next column — instead of sweeping the
        // slice in flat 1-block passes (layerHeight=1 would split a 2-tall slice into two horizontal sweeps).
        // The unbounded spiral keeps 1-block layers so its full-height chunk box still descends strictly
        // top-down a level at a time.
        pushBaritone(s, "layerheight", limitArea.get() ? layerThickness() : 1);
        pushBaritone(s, "breakfromabove", true);

        // Break gravel/sand instead of thrashing on it. Stock Baritone defaults avoidUpdatingFallingBlocks
        // = TRUE: it "will never break a block adjacent to an unsupported falling block" (to avoid cascading
        // sand/gravel falls). In a quarry every block in/around a gravel patch is adjacent to unsupported
        // gravel, so the builder refuses to commit and oscillates between candidate blocks forever, never
        // completing a break (the reported "stuck switching back and forth" on gravel). We WANT to clear it,
        // so turn that guard off. pauseMiningForFallingBlocks (held TRUE) keeps the bot patient: it breaks the
        // gravel, waits for the cascade to settle, then continues — no thrash. Both restored on deactivate.
        pushBaritone(s, "avoidupdatingfallingblocks", false);
        pushBaritone(s, "pauseminingforfallingblocks", true);

        // Cave handling: relax fall/jump limits so the bot drops into caverns to reach blocks. Lava is
        // never walked/fallen into regardless (those moves stay cost-infinity), and we force
        // assume-walk-on-lava off so a stray config can't make it lava-walk.
        if (caveHandling.get()) {
            pushBaritone(s, "maxfallheightnowater", maxFallHeight.get());
            pushBaritone(s, "allowparkour", allowParkour.get());
            pushBaritone(s, "allowparkourplace", allowParkourPlace.get());
            pushBaritone(s, "allowdiagonaldescend", allowDiagonalDescend.get());
            pushBaritone(s, "allowdiagonalascend", allowDiagonalAscend.get());
            pushBaritone(s, "assumewalkonlava", false);
            dbg("pushed cave settings: maxFall=%d parkour=%b parkourPlace=%b diagDesc=%b diagAsc=%b",
                maxFallHeight.get(), allowParkour.get(), allowParkourPlace.get(), allowDiagonalDescend.get(), allowDiagonalAscend.get());
        }
        dbg("pushed baritone (stock): allowbreak=true topDown=true(buildInLayers+layerOrder+breakFromAbove) layerHeight=%d(%s) breakGravel=true(avoidUpdatingFallingBlocks=false,pauseForSettle=true) failTO=1000ms caveHandling=%b floorY=%d",
            limitArea.get() ? layerThickness() : 1, limitArea.get() ? "tower-first per slice" : "1-block sweeps (unbounded)", caveHandling.get(), minYLevel.get());
    }

    /**
     * Set a Baritone setting by lowercase name, snapshotting its current value the FIRST time we touch
     * it this run so {@link #restoreBaritone()} can put it back on deactivate (no leaking of our pushes
     * into the user's manual Baritone use). Driven by name so the addon stays decoupled from Baritone's
     * mappings.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void pushBaritone(Settings s, String lowerName, Object value) {
        Settings.Setting setting = s.byLowerName.get(lowerName);
        if (setting == null) {
            warning("Baritone setting '%s' not found.", lowerName);
            return;
        }
        if (!savedBaritone.containsKey(lowerName)) savedBaritone.put(lowerName, setting.value);
        setting.value = value;
    }

    /**
     * Restore every Baritone setting we changed this run to its snapshotted original value.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void restoreBaritone() {
        if (savedBaritone.isEmpty()) return;
        Settings s = BaritoneAPI.getSettings();
        for (var e : savedBaritone.entrySet()) {
            Settings.Setting setting = s.byLowerName.get(e.getKey());
            if (setting != null) setting.value = e.getValue();
        }
        dbg("restored %d baritone setting(s) to their originals", savedBaritone.size());
        savedBaritone.clear();
    }

    // ---------------- Main loop ----------------

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Defer to item use (eating/drinking): hold the addon's OWN actions while a bite is in
        // progress, so we never restart mining mid-bite. This covers the addon's storage/deposit
        // breaks — but NOT the Baritone quarry, which runs on its own handler and keeps forcing the
        // attack click; the hunger pause below releases the quarry so the bite can finish.
        if (mc.player.isUsingItem()) return;

        // CornerSelect: idle until both corners are marked (handled by the input events + render),
        // and don't let the hazard check pause us mid-selection.
        if (state == State.SELECT) return;

        if (timer > 0) {
            timer--;
            return;
        }

        // Pause for a hazard (any state) OR for low hunger (only while MINING — the quarry is the
        // one that fights AutoEat; pausing mid storage/deposit cycle would strand a placed chest).
        boolean hazard = pauseOnHazard.get() && isHazard();
        boolean hungry = hungerPauseActive() && (state == State.MINING || state == State.PAUSED);
        if (hazard || hungry) {
            if (state != State.PAUSED) {
                warning(hazard ? "检测到危险 - 暂停。" : "饥饿较低 — 暂停，等待 AutoEat 进食。");
                stopMining();
                state = State.PAUSED;
                step = 0;
            }
            timer = pauseRetryTicks.get();
            return;
        }

        switch (state) {
            case MINING -> tickMining();
            case COLLECT -> tickCollect();
            case UNSTICK -> tickUnstick();
            case CLEAR_AREA -> tickClearArea();
            case PLACE_ECHEST -> tickPlaceEchest();
            case ECHEST_TAKE -> tickEchestTake();
            case PLACE_SHULKER -> tickPlaceShulker();
            case SHULKER_FILL -> tickShulkerFill();
            case BREAK_SHULKER -> tickBreakShulker();
            case PICKUP_SHULKER -> tickPickupShulker();
            case ECHEST_STORE -> tickEchestStore();
            case BREAK_ECHEST -> tickBreakEchest();
            case RESTOCK -> tickRestock();
            case FOOD_RESTOCK -> tickFoodRestock();
            case DEPOSIT -> tickDeposit();
            case PAUSED -> tickPaused();
            case DONE -> finishStorage();
        }
    }

    // ----- MINING -----

    /**
     * True if Meteor's AutoTool module is enabled — then it's the sole tool selector once Baritone's is off.
     */
    private boolean meteorAutoToolActive() {
        try {
            return meteordevelopment.meteorclient.systems.modules.Modules.get()
                .isActive(meteordevelopment.meteorclient.systems.modules.player.AutoTool.class);
        } catch (Throwable t) {
            dbg("meteorAutoToolActive check failed: %s", t.toString());
            return false;
        }
    }

    /**
     * Auto-heal the hotbar tool tug-of-war (see 'stop-tool-fight'). Sampled each mining tick: if the SELECTED
     * hotbar slot is changing many times a second, two tool-selectors (Baritone's auto-tool and Meteor's
     * AutoTool) are fighting and every break is being cancelled. We then disable Baritone's auto-tool (pushed
     * by name, snapshotted so deactivate restores it) so a single selector stays in control. Latches — once
     * suppressed this run, we stop checking. The addon never changes the selected slot during MINING itself,
     * so a thrash here is always external.
     */
    private void detectToolFight() {
        if (!stopToolFight.get() || baritoneAutoToolSuppressed) return;
        int sel = Via.getSelectedSlot();
        if (selSlotPrev != -1 && sel != selSlotPrev) toolSwitchCount++;
        selSlotPrev = sel;
        if (++toolWindowTicks >= TOOL_FIGHT_WINDOW) {
            if (toolSwitchCount >= TOOL_FIGHT_SWITCHES) {
                pushBaritone(BaritoneAPI.getSettings(), "autotool", false);
                baritoneAutoToolSuppressed = true;
                warning("Tool tug-of-war detected (hotbar slot thrashing %dx/s) — disabled Baritone's auto-tool so breaks can hold (Meteor AutoTool keeps selecting).", toolSwitchCount);
                dbg("tool-fight: %d selected-slot switches in %dt -> pushed baritone autotool=false", toolSwitchCount, TOOL_FIGHT_WINDOW);
            }
            toolSwitchCount = 0;
            toolWindowTicks = 0;
        }
    }

    private void tickMining() {
        detectToolFight();   // auto-heal the hotbar tool tug-of-war that freezes breaks (see 'stop-tool-fight')
        // One junk item per action-delay so dropping can never burst a stack of packets.
        if (dropJunk.get() && dropOneJunk()) {
            timer = nextDelay();
            return;
        }

        // Keep target blocks OUT of the hotbar (BepHax-style hotbar cleaning). Vanilla pickup
        // fills empty hotbar slots before main-inventory slots, so mined blocks pile into the
        // hotbar and leave no slot to stage the shulker/echest — the root cause of "a shulker
        // gets pulled but never placed". Shift one target stack from the hotbar back into the
        // main inventory per action-delay (one packet, never bursts).
        if (sweepHotbarTargets()) {
            timer = nextDelay();
            return;
        }

        // Genuinely-full trigger: fire only when empty MAIN-inventory slots run out (hotbar
        // excluded — it's kept clear of target and reserved for staging). Counting empties, not
        // occupancy, means reserved items (echest stack, spare tools, food) never trigger early,
        // and partial target stacks keep filling (vanilla merges into them before consuming an
        // empty) so we don't store until the inventory is actually full.
        boolean normalFull = requireFullStacks.get()
            ? !canHoldMoreTarget()                              // every target stack at 64, no empty slot
            : emptyMainSlots() <= freeSlotsBeforeStore.get();   // legacy: empty-slot margin
        // Overflow guard (Option A): with require-full-stacks the cycle waits for EVERY stack to hit 64.
        // But when one target type is far more abundant than another (e.g. cobbled deepslate vs tuff), the
        // abundant type fills the main inventory to 0 empties while the rare type leaves a partial stack
        // <64 — so canHoldMoreTarget() stays true and the bot spirals on forever. New abundant drops then
        // spill into the hotbar, where sweepHotbarTargets() above can't relocate them (main is full), and
        // once the hotbar fills too they despawn on the ground — large losses. Detect that exact state
        // (main full + target stranded in the hotbar) and store NOW regardless of partial stacks. The fill
        // pulls from the hotbar too (findPlayerSlotInContainer scans all 36 slots), so the overflow is
        // stored, not dropped.
        boolean overflowStranded = emptyMainSlots() == 0 && hotbarHasTarget();
        boolean invFull = normalFull || overflowStranded;
        if (invFull && hasTargetStacks()) {                      // never start a cycle with nothing to store
            stopMining();
            if (!normalFull && overflowStranded)
                info("Inventory packed and target overflowing into the hotbar — storing now instead of spiralling to top off a partial stack.");
            dbg("inventory full (%s%s) — starting storage cycle",
                requireFullStacks.get() ? "all stacks at 64" : "emptyMain<=" + freeSlotsBeforeStore.get(),
                overflowStranded ? " / overflow-guard" : "");
            if (countItem(Items.ENDER_CHEST) == 0) {
                pause("No ender chest in inventory.");
                return;
            }
            restocking = false;                                  // CLEAR_AREA routes to the storage cycle
            go(State.CLEAR_AREA);
            return;
        }

        // Tool-shulker restock: no fresh tool of the chosen type left anywhere, and an ender chest on
        // hand to crack open the tool-shulker. Reuses CLEAR_AREA for pocket prep, then runs the RESTOCK
        // FSM (which leaves the haul untouched — it only swaps a fresh tool in).
        if (restockFromShulker.get() && toolNeedsRestock() && countItem(Items.ENDER_CHEST) > 0) {
            stopMining();
            dbg("no fresh tool left — starting tool-shulker restock cycle");
            restocking = true;                                   // CLEAR_AREA routes to the restock FSM
            restockAbort = null;
            restockPhase = RestockPhase.PLACE_ECHEST;
            go(State.CLEAR_AREA);
            return;
        }

        // Food-shulker restock: out of food (good food on hand below the threshold) and an ender chest in
        // hand to crack the food-shulker. Its own cycle, independent of the loot/tool cycles — fires on food
        // COUNT, not hunger, so a fresh stack is staged before the hunger pause ever triggers. Best-effort:
        // a missing/empty food-shulker warns + latches rather than stalling the run.
        if (restockFood.get() && !foodRestockExhausted
            && countGoodFood() < minFoodOnHand.get()
            && countItem(Items.ENDER_CHEST) > 0) {
            stopMining();
            dbg("low on food (%d good-food item(s)) — starting food-shulker restock cycle", countGoodFood());
            foodRestocking = true;                               // CLEAR_AREA routes to the food FSM
            restocking = false;
            foodRestockAbort = null;
            foodTookAny = false;
            foodShulkerEmptied = false;
            foodPhase = FoodPhase.PLACE_ECHEST;
            go(State.CLEAR_AREA);
            return;
        }

        // Mining is the stock-Baritone ClearArea quarry: clear the current chunk box, then spiral.
        tickClearAreaMining();
    }

    // ----- CLEAR_AREA: open a pocket so the echest + shulker both have somewhere to go -----

    private void tickClearArea() {
        // Tidy the hotbar: shift a target block the hotbar picked up during mining back into the main
        // inventory when there's room. (Staging the echest/shulker no longer needs a *free* hotbar slot —
        // tickPlace swaps into the hotbar if it's full — so this is just housekeeping, never a blocker.)
        if (sweepHotbarTargets()) {
            timer = nextDelay();
            return;
        }

        BlockPos feet = mc.player.getBlockPos();
        // Break the four horizontal neighbours at feet + head height, leaving the floor
        // (feet.down()) intact. Breaks continuously (every tick) like normal mining, and
        // only touches solid non-bedrock blocks — an already-open area is a no-op.
        for (Direction dir : HORIZONTAL) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos p = feet.offset(dir).up(dy);
                if (p.getY() < minYLevel.get()) continue;
                var st = mc.world.getBlockState(p);
                if (!st.isReplaceable() && st.getBlock() != Blocks.BEDROCK) {
                    BlockUtils.breakBlock(p, true);
                    return; // continuous; resume next tick (no action-delay during breaking)
                }
            }
        }
        go(foodRestocking ? State.FOOD_RESTOCK : restocking ? State.RESTOCK : State.PLACE_ECHEST); // pocket cleared
        timer = nextDelay();
    }

    // ----- PLACE_ECHEST (select -> place -> swapBack, one packet per tick) -----

    private void tickPlaceEchest() {
        switch (tickPlace(s -> s.getItem() == Items.ENDER_CHEST)) {
            case DONE -> {
                placedEchest = pendingPlace;
                dbg("placed ender chest at %s", placedEchest.toShortString());
                go(State.ECHEST_TAKE);
                timer = nextDelay();
            }
            case FAILED -> pause("Couldn't place ender chest — " + placeFail + ".");
            case BUSY -> {
            }
        }
    }

    // ----- ECHEST_TAKE: open, restock pickaxe, stock carried empties, pull one empty shulker -----

    private void tickEchestTake() {
        switch (step) {
            case 0 -> {
                openBlock(placedEchest);
                step = 1;
                timer = nextDelay();
            }
            case 1 -> {
                if (isContainerOpen()) {
                    step = 2;
                    attempts = 0;
                    timer = nextDelay();
                } else if (++attempts >= 20) pause("Ender chest didn't open.");
                else timer = nextDelay();
            }
            case 2 -> { // restock pickaxe if low (at most one shift-move)
                if (!isContainerOpen()) {
                    pause("Ender chest closed unexpectedly.");
                    return;
                }
                restockTool(mc.player.currentScreenHandler);
                step = depositActive ? 3 : 4; // deposit mode: stock empties from a refill back into the echest first
                timer = nextDelay();
            }
            case 3 -> { // STOCK: push any carried empty shulkers INTO the echest (echest = the field supply)
                if (!isContainerOpen()) {
                    pause("Ender chest closed unexpectedly.");
                    return;
                }
                ScreenHandler h = mc.player.currentScreenHandler;
                int playerSlot = findPlayerSlotInContainer(h, this::isEmptyShulkerStack);
                if (playerSlot >= 0 && containerHasRoomFor(h, h.slots.get(playerSlot).getStack())) {
                    quickMove(h, playerSlot);       // one carried empty into the echest, then loop
                    timer = fillMoveDelay();
                    return;
                }
                step = 4;
                timer = nextDelay();      // nothing more to stock (or echest full)
            }
            case 4 -> { // pull one empty shulker straight into the HOTBAR (so it can be placed), or finish
                if (!isContainerOpen()) {
                    pause("Ender chest closed unexpectedly.");
                    return;
                }
                ScreenHandler h = mc.player.currentScreenHandler;
                int shulkerSlot = findContainerSlot(h, this::isEmptyShulker);
                if (shulkerSlot >= 0) {
                    int free = freeHotbarSlot();
                    if (free != -1) swapToHotbar(h, shulkerSlot, free); // one packet, lands in hotbar
                    else
                        quickMove(h, shulkerSlot);                     // fallback: at least pull it out (PLACE_SHULKER then stages it)
                    dbg("pulled empty shulker to hotbar slot %d", free);
                    step = 5;
                } else if (countShulkers(this::isEmptyShulkerInInv) > 0) {
                    dbg("echest empty but a carried empty remains — using it"); // echest was full, couldn't stock it
                    step = 5; // skip the pull; PLACE_SHULKER finds the carried empty
                } else {
                    dbg("no empty shulkers left in ender chest");
                    step = 6;
                }
                timer = nextDelay();
            }
            case 5 -> {
                closeScreen();
                go(State.PLACE_SHULKER);
                timer = nextDelay();
            }
            default -> { // 6: no empty shulkers anywhere
                closeScreen();
                if (depositActive) {
                    // Out of empties with a full load of haul in hand: the proactive trip (detected in
                    // tickEchestStore, when the inventory was clear) should normally fire first, so this
                    // is the cold-start fallback (no empties were ever loaded). Can't pack the haul.
                    pause("Out of empty shulkers — none in the ender chest or inventory. "
                        + "Load empty shulkers (or stock a supply chest), then toggle off/on.");
                } else if (countShulkers(this::isFilledShulkerInInv) > 0)
                    go(State.BREAK_SHULKER); // store what we have first
                else if (countShulkers(this::isEmptyShulkerInInv) > 0)
                    go(State.PLACE_SHULKER); // use an inventory spare
                else {
                    doneReason = "no empty shulkers left in the ender chest";
                    go(State.DONE);
                }
                timer = nextDelay();
            }
        }
    }

    // ----- PLACE_SHULKER -----

    private void tickPlaceShulker() {
        switch (tickPlace(this::isEmptyShulkerStack)) {
            case DONE -> {
                placedShulker = pendingPlace;
                dbg("placed shulker at %s", placedShulker.toShortString());
                go(State.SHULKER_FILL);
                timer = nextDelay();
            }
            case FAILED -> pause("Couldn't place shulker — " + placeFail + ".");
            case BUSY -> {
            }
        }
    }

    // ----- SHULKER_FILL: open, then shift one target stack per tick -----

    private void tickShulkerFill() {
        switch (step) {
            case 0 -> {
                openBlock(placedShulker);
                step = 1;
                timer = nextDelay();
            }
            case 1 -> {
                if (isContainerOpen()) {
                    step = 2;
                    attempts = 0;
                    timer = nextDelay();
                } else if (++attempts >= 20) pause("Shulker didn't open.");
                else timer = nextDelay();
            }
            case 2 -> { // move ONE target stack into the shulker per tick (only target ever moves)
                if (!isContainerOpen()) {
                    pause("Shulker closed unexpectedly.");
                    return;
                }
                ScreenHandler h = mc.player.currentScreenHandler;
                int playerSlot = findPlayerSlotInContainer(h, itemStack -> isTargetStack(itemStack) && !keepInventoryItems.get().contains(itemStack.getItem()));
                if (playerSlot < 0) {
                    step = 3;
                    timer = nextDelay();
                    return;
                }
                // no target left → sealed
                // Destination-aware: only shift if the shulker can actually take this stack. If it's
                // full, stop here — the post-store loop will place a fresh shulker and keep going.
                if (!containerHasRoomFor(h, h.slots.get(playerSlot).getStack())) {
                    step = 3;
                    timer = nextDelay();
                    return;
                }
                quickMove(h, playerSlot);     // QUICK_MOVE moves only the clicked (target) stack
                timer = fillMoveDelay();       // faster cadence for in-container transfers
            }
            default -> {
                closeScreen();
                go(State.BREAK_SHULKER);
                timer = nextDelay();
            } // 3
        }
    }

    // ----- BREAK_SHULKER -----

    private void tickBreakShulker() {
        if (placedShulker != null
            && mc.world.getBlockState(placedShulker).getBlock() instanceof ShulkerBoxBlock) {
            if (!pickupSnapshotTaken) {
                beginPickup(this::isFilledShulkerInInv);
                pickupSnapshotTaken = true;
            } // snapshot once, before it pops
            BlockUtils.breakBlock(placedShulker, true); // continuous until it pops (drops filled)
            return; // breaking must run every tick to make progress — no action-delay here
        }
        if (pickupSnapshotTaken) {
            // We actually broke a placed shulker — chase + collect the drop before reopening the echest. A
            // broken shulker can bounce a block off the break spot and 2b's pickup physics won't grab it
            // from a stationary stand, so the bot walks onto it (else ECHEST_STORE finds nothing and the
            // filled shulker is left behind). The filled shulker always goes back into the ender chest (the
            // field buffer); it never sits in the working inventory during mining.
            pickupSnapshotTaken = false;
            go(State.PICKUP_SHULKER);
        } else {
            // Nothing was placed/broken this visit — we already hold filled shulker(s) — just store them.
            placedShulker = null;
            go(State.ECHEST_STORE);
        }
        timer = nextDelay();
    }

    // ----- PICKUP_SHULKER: walk onto the broken filled shulker before reopening the echest -----

    private void tickPickupShulker() {
        switch (tickPickupDrop(placedShulker, this::isFilledShulkerInInv)) {
            case BUSY -> {
            }
            case GAVE_UP -> {
                dbg("pickup: gave up chasing the filled shulker — storing what's in hand");
                placedShulker = null;
                go(State.ECHEST_STORE);
            }
            case DONE -> {
                placedShulker = null;
                go(State.ECHEST_STORE);
            }
        }
        timer = nextDelay();
    }

    // ----- ECHEST_STORE: reopen echest, push filled shulker(s) in one per tick -----

    private void tickEchestStore() {
        switch (step) {
            case 0 -> {
                openBlock(placedEchest);
                echestStoredBefore = -1;
                step = 1;
                timer = nextDelay();
            }
            case 1 -> {
                if (isContainerOpen()) {
                    step = 2;
                    attempts = 0;
                    timer = nextDelay();
                } else if (++attempts >= 20) pause("Ender chest didn't reopen.");
                else timer = nextDelay();
            }
            case 2 -> { // one filled shulker into the echest per tick
                if (!isContainerOpen()) {
                    pause("Ender chest closed unexpectedly.");
                    return;
                }
                ScreenHandler h = mc.player.currentScreenHandler;
                // Confirm the previous shift-click actually landed in the chest before issuing the next or
                // finishing — otherwise a rejected last move would close the chest with the shulker still in
                // hand (carried, not stored).
                if (echestStoredBefore >= 0) {
                    if (countContainerMatches(h, this::isFilledShulkerStack) > echestStoredBefore) {
                        echestStoredBefore = -1;
                        attempts = 0;   // confirmed stored — look for the next one
                    } else if (++attempts > 20 * 3) {
                        dbg("store: filled-shulker store not confirmed after 3s — retrying"); // re-issue below
                        echestStoredBefore = -1;
                        attempts = 0;
                    } else {
                        timer = nextDelay();
                        return;
                    }
                }
                int playerSlot = findPlayerSlotInContainer(h, this::isFilledShulkerStack);
                if (playerSlot < 0) {
                    step = 3;
                    timer = nextDelay();
                    return;
                } // nothing left to store
                if (!containerHasRoomFor(h, h.slots.get(playerSlot).getStack())) { // echest full of shulkers
                    if (depositActive) {
                        // Full of filled shulkers IS the trip trigger. Recover the echest with a clean
                        // inventory, then resumeMining() starts the deposit trip (so we never keep mining
                        // and pack the inventory with blocks before there's room to pull the shulkers out).
                        echestExhausted = true;
                        info("Ender chest is full of filled shulkers — heading out on a deposit trip.");
                        closeScreen();
                        go(State.BREAK_ECHEST);
                        timer = nextDelay();
                        return;
                    }
                    closeScreen();
                    doneReason = "ender chest full of filled shulkers";
                    go(State.DONE);
                    timer = nextDelay();
                    return;
                }
                echestStoredBefore = countContainerMatches(h, this::isFilledShulkerStack); // confirm it lands next tick
                quickMove(h, playerSlot);
                timer = fillMoveDelay();
            }
            default -> { // 3: done storing — detect echest exhaustion, then loop another shulker or recover
                ScreenHandler h = mc.player.currentScreenHandler;
                // Deposit mode: with the inventory now clear, if the echest holds no empty shulker (and we
                // aren't carrying one), it's exhausted — resumeMining() will start a deposit trip from this
                // clean state (so the EXTRACT leg has room to pull the filled shulkers back out).
                if (depositActive && isContainerOpen()
                    && findContainerSlot(h, this::isEmptyShulker) == -1
                    && countShulkers(this::isEmptyShulkerInInv) == 0) {
                    echestExhausted = true;
                    info("Used the last empty shulker — ender chest is full; making a deposit trip.");
                }
                closeScreen();
                if (!echestExhausted && countShulkers(this::isEmptyShulkerInInv) > 0 && hasTargetStacks())
                    go(State.PLACE_SHULKER);
                else go(State.BREAK_ECHEST);
                timer = nextDelay();
            }
        }
    }

    // ----- BREAK_ECHEST -----

    private void tickBreakEchest() {
        if (!breakAndCollectEchest()) return; // break + chase the ender chest drop before resuming
        resumeMining();
        timer = nextDelay();
    }

    private void resumeMining() {
        if (areaComplete) {                 // bounded area finished (this was the final storage cycle)
            // Deposit mode: deliver the last shulkers (stored in the echest) before stopping — the trip's
            // EXTRACT leg pulls them out; if the echest holds none it falls straight through to DONE.
            if (depositActive && !depositChestList().isEmpty()) {
                dbg("area complete -> final deposit trip");
                finishAfterDeposit = true;
                beginDepositTrip();
                return;
            }
            dbg("area complete after final storage -> DONE");
            doneReason = "custom area fully cleared";
            go(State.DONE);
            return;
        }
        // Deposit-chest mode: the ender chest has run out of empty shulkers (it's full of filled ones) ->
        // make a trip to haul the filled shulkers out to a deposit chest and refill empties.
        if (depositActive && echestExhausted) {
            echestExhausted = false;
            beginDepositTrip();
            return;
        }
        dbg("resume mining at chunk [%d,%d]", curCX, curCZ);
        go(State.MINING);
        startMining();
    }

    /**
     * Storage is exhausted: stop the module, and (if 'auto-disconnect' is on) leave the server too.
     */
    private void finishStorage() {
        boolean disconnect = autoDisconnect.get();
        dbg("DONE (%s) -> %s", doneReason, disconnect ? "disconnect + toggle off" : "toggle off");
        info("Storage done: %s.%s", doneReason, disconnect ? " Disconnecting." : "");
        if (disconnect) disconnectFromServer("Foreman MassExtractor: " + doneReason);
        toggle();
    }

    /**
     * Cleanly leave the current server with a reason shown on the disconnect screen.
     */
    private void disconnectFromServer(String reason) {
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().getConnection().disconnect(Text.literal(reason));
        }
    }

    // ----- PAUSED -----

    private void tickPaused() {
        boolean hazard = pauseOnHazard.get() && isHazard();
        boolean hungry = hungerPauseActive();
        if (!hazard && !hungry) {
            info("Clear — resuming mining.");
            resumeMining();
        } else {
            timer = pauseRetryTicks.get();
        }
    }

    // ---------------- Deposit-chest trips (optional) ----------------
    // The ender chest is the FIELD buffer: during mining, filled shulkers are stored into it and empties
    // taken from it (exactly like the EnderChest cycle). A trip only happens once the echest runs out of
    // empties (it's full of filled shulkers) — detected the moment the last empty is used, with a clean
    // inventory, so the bot never keeps mining and packs blocks before there's room to extract. The trip
    // walks to the nearest DEPOSIT chest FIRST with an empty inventory (the filled shulkers stay safe in
    // the global echest until the bot is there — a death en route can't strand them), then at the chest
    // EXTRACTs the filled shulkers out of the echest (place echest -> open -> pull -> break) and dumps
    // them in. If refill-empties is on it then walks to the nearest SUPPLY chest — a separate chest — and
    // takes ONLY as many empties as will fit in the echest after the deposit, STOCKs those straight into
    // the echest, and heads back to mining. Every leg has a stuck-timeout that moves on to the next chest
    // or pauses, so a wrong/blocked/out-of-range chest can't hang the run.

    private boolean hasAnyEmptyShulker() {
        return countShulkers(this::isEmptyShulkerInInv) > 0;
    }

    /**
     * Begin a deposit trip: walk to the nearest deposit chest FIRST — the filled shulkers stay in the
     * ender chest (global) until the bot is standing at the chest, so a death en route can't strand them.
     */
    private void beginDepositTrip() {
        stopMining();
        triedChests.clear();
        depositTravelTicks = 0;
        lastTravelPos = null;
        tripExtracted = false;
        tripRefilled = false;
        echestFreeAfterExtract = 0;
        go(State.DEPOSIT);
        if (countItem(Items.ENDER_CHEST) == 0) {
            pause("No ender chest in inventory to open the shulker buffer.");
            return;
        }
        depositChest = nearestChest(depositChestList());
        if (depositChest == null) {
            pause(noChestMsg(depositChestList(), "deposit"));
            return;
        }
        setDepositPhase(DepositPhase.PATH_TO_DEPOSIT);
        info("Deposit trip: walking to the deposit chest at %d, %d, %d (filled shulkers stay in the ender chest until I'm there).",
            depositChest.getX(), depositChest.getY(), depositChest.getZ());
    }

    /**
     * Empties to take this trip: capped to what will fit in the echest after the deposit (and empties-per-trip).
     */
    private int tripEmptiesTarget() {
        return Math.min(emptiesPerTrip.get(), echestFreeAfterExtract);
    }

    /**
     * Go stock carried empties into the echest, or finish the trip if there are none.
     */
    private void gotoStockOrDone() {
        if (countShulkers(this::isEmptyShulkerInInv) > 0 && countItem(Items.ENDER_CHEST) > 0) {
            tripRefilled = true;                 // we obtained empties; they'll be stocked into the echest
            setDepositPhase(DepositPhase.STOCK_PLACE);
        } else setDepositPhase(DepositPhase.DONE);
    }

    /**
     * After the filled shulkers are dumped: refill empties (if wanted and the echest has room), else stock/finish.
     */
    private void afterDeposit() {
        if (!finishAfterDeposit && refillEmpties.get()
            && tripEmptiesTarget() > 0
            && countShulkers(this::isEmptyShulkerInInv) < tripEmptiesTarget()) {
            startSupplyLeg();
        } else {
            gotoStockOrDone();
        }
        timer = nextDelay();
    }

    // ----- EXTRACT leg (standing at the deposit chest): place the echest, pull the filled loot shulkers out, break it -----

    private void tickPullPlace() {
        switch (tickPlace(s -> s.getItem() == Items.ENDER_CHEST)) {
            case DONE -> {
                placedEchest = pendingPlace;
                dbg("placed ender chest at %s (extract)", placedEchest.toShortString());
                setDepositPhase(DepositPhase.PULL_OPEN);
                timer = nextDelay();
            }
            case FAILED -> pause("Couldn't place the ender chest to collect filled shulkers.");
            case BUSY -> {
            }
        }
    }

    private void tickPullOpen() {
        if (step == 0) {
            openBlock(placedEchest);
            step = 1;
            timer = nextDelay();
        } else if (isContainerOpen()) {
            setDepositPhase(DepositPhase.PULL_FILLED);
            timer = nextDelay();
        } else if (++attempts >= 20) pause("Ender chest didn't open (collecting filled shulkers).");
        else timer = nextDelay();
    }

    /**
     * Pull filled loot shulkers (NOT the tool-shulker) out of the open echest, one per tick.
     */
    private void tickPullFilled() {
        if (!isContainerOpen()) {
            pause("Ender chest closed unexpectedly (collecting filled shulkers).");
            return;
        }
        ScreenHandler h = mc.player.currentScreenHandler;
        int slot = findContainerSlot(h, this::isLootFilledShulker);
        if (slot < 0 || (emptyMainSlots() == 0 && freeHotbarSlot() == -1)) {   // pulled them all (or inventory full, rare)
            echestFreeAfterExtract = echestFreeSlots(h);                        // record how many empties may be refilled
            setDepositPhase(DepositPhase.PULL_CLOSE);
            timer = nextDelay();
            return;
        }
        quickMove(h, slot);                   // echest -> player inventory
        timer = fillMoveDelay();
    }

    private void tickPullClose() {
        closeScreen();
        setDepositPhase(DepositPhase.PULL_BREAK);
        timer = nextDelay();
    }

    private void tickPullBreak() {
        if (!breakAndCollectEchest()) return; // break + chase the ender chest drop before dumping
        tripExtracted = true;
        if (countShulkers(this::isLootFilledShulker) > 0) {
            setDepositPhase(DepositPhase.OPEN_DEPOSIT);  // open the deposit chest and dump them
            timer = nextDelay();
        } else {
            afterDeposit();                              // echest held no filled shulkers (edge) — skip the dump
        }
    }

    // ----- STOCK leg: put the refilled empty shulkers into the ender chest (so the echest stays the field supply) -----

    private void tickStockPlace() {
        switch (tickPlace(s -> s.getItem() == Items.ENDER_CHEST)) {
            case DONE -> {
                placedEchest = pendingPlace;
                dbg("placed ender chest at %s (stock)", placedEchest.toShortString());
                setDepositPhase(DepositPhase.STOCK_OPEN);
                timer = nextDelay();
            }
            case FAILED -> pause("Couldn't place the ender chest to stock empty shulkers.");
            case BUSY -> {
            }
        }
    }

    private void tickStockOpen() {
        if (step == 0) {
            openBlock(placedEchest);
            step = 1;
            timer = nextDelay();
        } else if (isContainerOpen()) {
            setDepositPhase(DepositPhase.STOCK_EMPTIES);
            timer = nextDelay();
        } else if (++attempts >= 20) pause("Ender chest didn't open (stocking empty shulkers).");
        else timer = nextDelay();
    }

    /**
     * Push carried empty shulkers into the open echest, one per tick (they fit — the take was capped to the free space).
     */
    private void tickStockEmpties() {
        if (!isContainerOpen()) {
            pause("Ender chest closed unexpectedly (stocking empty shulkers).");
            return;
        }
        ScreenHandler h = mc.player.currentScreenHandler;
        int playerSlot = findPlayerSlotInContainer(h, this::isEmptyShulkerStack);
        if (playerSlot < 0 || !containerHasRoomFor(h, h.slots.get(playerSlot).getStack())) { // all stocked (or echest full)
            setDepositPhase(DepositPhase.STOCK_CLOSE);
            timer = nextDelay();
            return;
        }
        quickMove(h, playerSlot);
        timer = fillMoveDelay();
    }

    private void tickStockClose() {
        closeScreen();
        setDepositPhase(DepositPhase.STOCK_BREAK);
        timer = nextDelay();
    }

    private void tickStockBreak() {
        if (!breakAndCollectEchest()) return; // break + chase the ender chest drop before finishing the trip
        setDepositPhase(DepositPhase.DONE);
        timer = nextDelay();
    }

    private String noChestMsg(List<BlockPos> list, String kind) {
        return list.isEmpty()
            ? "No " + kind + " chests marked — aim at one and press the mark-" + kind + " bind."
            : "No " + kind + " chest within range.";
    }

    /**
     * Advance to a deposit sub-phase, resetting the shared step/attempts counters.
     */
    private void setDepositPhase(DepositPhase p) {
        if (p != depositPhase) dbg("deposit %s -> %s", depositPhase, p);
        depositPhase = p;
        step = 0;
        attempts = 0;
    }

    private void tickDeposit() {
        switch (depositPhase) {
            case PATH_TO_DEPOSIT, PATH_TO_SUPPLY -> tickDepositTravel();
            case PULL_PLACE -> tickPullPlace();
            case PULL_OPEN -> tickPullOpen();
            case PULL_FILLED -> tickPullFilled();
            case PULL_CLOSE -> tickPullClose();
            case PULL_BREAK -> tickPullBreak();
            case OPEN_DEPOSIT, OPEN_SUPPLY -> tickDepositOpen();
            case DEPOSIT_FILLED -> tickDepositFilled();
            case TAKE_EMPTIES -> tickTakeEmpties();
            case STOCK_PLACE -> tickStockPlace();
            case STOCK_OPEN -> tickStockOpen();
            case STOCK_EMPTIES -> tickStockEmpties();
            case STOCK_CLOSE -> tickStockClose();
            case STOCK_BREAK -> tickStockBreak();
            case CLOSE_DEPOSIT -> {
                closeScreen();
                afterDeposit();
            }
            case CLOSE_SUPPLY -> {
                closeScreen();
                gotoStockOrDone();
                timer = nextDelay();
            }
            default -> { // DONE: head back to mining (clearArea re-paths to the box). Guard against spinning.
                if (finishAfterDeposit) {           // bounded-area final trip: last haul delivered, stop now
                    finishAfterDeposit = false;
                    doneReason = "custom area fully cleared";
                    go(State.DONE);
                    return;
                }
                if (depositActive && !tripRefilled) {
                    // trip stocked no empties into the echest (supply empty / refill off) -> can't keep mining.
                    // Pause now rather than mine a full load of blocks first and get stuck with no empty to pack.
                    pause(refillEmpties.get()
                        ? "Out of empty shulkers — no supply chest had any."
                        : "Out of empty shulkers, and refill-from-chests is off.");
                    return;
                }
                dbg("deposit trip done — resuming mining");
                go(State.MINING);
                startMining();
            }
        }
    }

    /**
     * Walk to depositChest (both legs); on arrival open it, on a stuck walk try the next chest.
     */
    private void tickDepositTravel() {
        boolean supplyLeg = depositPhase == DepositPhase.PATH_TO_SUPPLY;
        if (step == 0) {
            if (!pathToNear(depositChest, 2)) {
                pause("Couldn't start pathing to the chest.");
                return;
            }
            step = 1;
            lastTravelPos = null;
            depositTravelTicks = 0;
            return; // poll arrival each tick (no packets)
        }
        if (Math.sqrt(mc.player.getBlockPos().getSquaredDistance(depositChest)) <= 4.0) {
            baritone.getPathingBehavior().cancelEverything();
            if (supplyLeg) setDepositPhase(DepositPhase.OPEN_SUPPLY);
                // first time at a deposit chest: pull the filled shulkers out of the echest here; if we've
                // already extracted (moved on to another deposit chest because the first filled up), just open it.
            else setDepositPhase(tripExtracted ? DepositPhase.OPEN_DEPOSIT : DepositPhase.PULL_PLACE);
            timer = nextDelay();
            return;
        }
        BlockPos p = mc.player.getBlockPos();
        if (lastTravelPos == null || !p.equals(lastTravelPos)) {
            lastTravelPos = p;
            depositTravelTicks = 0;
        } else if (++depositTravelTicks > TRAVEL_STALL_TICKS) {
            warning("Couldn't reach the %s chest (no movement %ds) — trying another.", supplyLeg ? "supply" : "deposit", TRAVEL_STALL_TICKS / 20);
            baritone.getPathingBehavior().cancelEverything();
            tryNextChest(supplyLeg);
        }
    }

    /**
     * Open depositChest; on failure try the next chest of this leg.
     */
    private void tickDepositOpen() {
        boolean supplyLeg = depositPhase == DepositPhase.OPEN_SUPPLY;
        if (step == 0) {
            openBlock(depositChest);
            step = 1;
            timer = nextDelay();
        } else if (isContainerOpen()) {
            setDepositPhase(supplyLeg ? DepositPhase.TAKE_EMPTIES : DepositPhase.DEPOSIT_FILLED);
            timer = nextDelay();
        } else if (++attempts >= 20) {
            warning("Couldn't open the %s chest — trying another.", supplyLeg ? "supply" : "deposit");
            tryNextChest(supplyLeg);
        } else timer = nextDelay();
    }

    /**
     * Shift filled loot shulkers into the open deposit chest, one per tick; full -> next chest.
     */
    private void tickDepositFilled() {
        if (!isContainerOpen()) {
            pause("Deposit chest closed unexpectedly.");
            return;
        }
        ScreenHandler h = mc.player.currentScreenHandler;
        int playerSlot = findPlayerSlotInContainer(h, this::isLootFilledShulker);
        if (playerSlot < 0) {
            setDepositPhase(DepositPhase.CLOSE_DEPOSIT);
            timer = nextDelay();
            return;
        } // all dropped off
        if (!containerHasRoomFor(h, h.slots.get(playerSlot).getStack())) {                                 // this chest is full
            closeScreen();
            tryNextChest(false);
            return;
        }
        quickMove(h, playerSlot);
        timer = fillMoveDelay();
    }

    /**
     * Pull empty shulkers out of the open supply chest, one per tick, up to what will fit in the echest.
     */
    private void tickTakeEmpties() {
        if (!isContainerOpen()) {
            pause("Supply chest closed unexpectedly.");
            return;
        }
        // only take as many empties as will fit in the (now mostly empty) ender chest after the deposit
        if (countShulkers(this::isEmptyShulkerInInv) >= tripEmptiesTarget()) {
            setDepositPhase(DepositPhase.CLOSE_SUPPLY);
            timer = nextDelay();
            return;
        }
        if (emptyMainSlots() == 0 && freeHotbarSlot() == -1) {
            setDepositPhase(DepositPhase.CLOSE_SUPPLY);
            timer = nextDelay();
            return;
        } // inventory full
        ScreenHandler h = mc.player.currentScreenHandler;
        int slot = findContainerSlot(h, this::isEmptyShulker);
        if (slot < 0) {                                                  // this supply chest is out of empties
            closeScreen();
            triedChests.add(depositChest);
            BlockPos next = nearestChest(supplyChestList());
            if (next == null) {
                gotoStockOrDone();
                timer = nextDelay();
                return;
            } // stock what we already grabbed
            depositChest = next;
            depositTravelTicks = 0;
            lastTravelPos = null;
            setDepositPhase(DepositPhase.PATH_TO_SUPPLY);
            timer = nextDelay();
            return;
        }
        quickMove(h, slot);                                              // container -> player inventory
        timer = fillMoveDelay();
    }

    /**
     * Start the supply leg (refill empties) by routing to the nearest supply chest.
     */
    private void startSupplyLeg() {
        triedChests.clear();
        BlockPos s = nearestChest(supplyChestList());
        if (s == null) {
            // No reachable supply chest: stock any empties we already carry, else pause.
            if (hasAnyEmptyShulker()) gotoStockOrDone();
            else pause(noChestMsg(supplyChestList(), "supply"));
            return;
        }
        depositChest = s;
        depositTravelTicks = 0;
        lastTravelPos = null;
        setDepositPhase(DepositPhase.PATH_TO_SUPPLY);
    }

    /**
     * A chest of the current leg was full/unreachable: try the next nearest, else finish or pause.
     */
    private void tryNextChest(boolean supplyLeg) {
        triedChests.add(depositChest);
        BlockPos next = nearestChest(supplyLeg ? supplyChestList() : depositChestList());
        if (next == null) {
            if (supplyLeg) {
                if (hasAnyEmptyShulker()) gotoStockOrDone();
                else pause("No reachable supply chest with empty shulkers.");
            } else {
                if (countShulkers(this::isLootFilledShulker) == 0) gotoStockOrDone();
                else pause("No reachable deposit chest with room.");
            }
            return;
        }
        depositChest = next;
        depositTravelTicks = 0;
        lastTravelPos = null;
        setDepositPhase(supplyLeg ? DepositPhase.PATH_TO_SUPPLY : DepositPhase.PATH_TO_DEPOSIT);
        timer = nextDelay();
    }

    /**
     * Path the bot to within {@code range} blocks of {@code pos} using Baritone's GoalNear. GoalNear's
     * constructor takes a BlockPos (an MC type the unmapped runtime Baritone names differently), so the
     * goal is built reflectively — the same reason {@link #callClearArea} is. The process call itself
     * uses Baritone API types directly (those resolve fine at runtime, like getBuilderProcess()).
     */
    private boolean pathToNear(BlockPos pos, int range) {
        try {
            Class<?> goalNear = Class.forName("baritone.api.pathing.goals.GoalNear");
            Object goal = goalNear.getConstructor(BlockPos.class, int.class).newInstance(pos, range);
            baritone.getCustomGoalProcess().setGoalAndPath((baritone.api.pathing.goals.Goal) goal);
            return true;
        } catch (Exception e) {
            dbg("pathToNear reflection failed: %s", e.toString());
            return false;
        }
    }

    /**
     * Path the bot to stand ON {@code pos} (Baritone's GoalBlock — exact block, not "near"). Used to
     * vacuum drops: the bot walks right onto/through the item's block so vanilla pickup fires even when
     * 2b's pickup physics won't grab it from a block away. Reflective for the same reason as pathToNear.
     */
    private boolean pathToBlock(BlockPos pos) {
        try {
            Class<?> goalBlock = Class.forName("baritone.api.pathing.goals.GoalBlock");
            Object goal = goalBlock.getConstructor(BlockPos.class).newInstance(pos);
            baritone.getCustomGoalProcess().setGoalAndPath((baritone.api.pathing.goals.Goal) goal);
            return true;
        } catch (Exception e) {
            dbg("pathToBlock reflection failed: %s", e.toString());
            return false;
        }
    }

    // ---------------- Placement sub-routine (3 ticks: select, place, swapBack) ----------------

    /**
     * Desync-safe placement of the item matching {@code pred}. Picks a spot, makes sure the
     * item is in the HOTBAR (shift-clicked pulls land in main inventory, which InvUtils.swap
     * can't select — this was the "shulker stuck in main inventory" bug), then places it.
     * Uses {@link #step}, so the caller must be a dedicated state; {@link #pendingPlace} holds
     * the spot on DONE. Every dead end returns FAILED so the caller can pause + recover.
     */
    private Place tickPlace(java.util.function.Predicate<ItemStack> pred) {
        switch (step) {
            case 0 -> { // choose a spot
                placeTried.clear();
                pendingPlace = findPlacementSpot(placeTried);
                if (pendingPlace == null) {
                    placeFail = "no open spot beside the bot";
                    return Place.FAILED;
                }
                step = 1;
                attempts = 0;
                timer = nextDelay();
                return Place.BUSY;
            }
            case 1 -> { // ensure the item is in the hotbar (so InvUtils/BlockUtils can select + place it)
                if (InvUtils.findInHotbar(pred).found()) {
                    step = 3;
                    attempts = 0;
                    timer = nextDelay();
                    return Place.BUSY;
                }
                int free = freeHotbarSlot();
                if (free != -1) {                                 // a free hotbar slot -> simple move
                    FindItemResult any = InvUtils.find(pred);
                    if (!any.found()) {
                        placeFail = "lost the item";
                        return Place.FAILED;
                    }
                    InvUtils.move().from(any.slot()).toHotbar(free);
                    attempts = 0;
                    step = 2;
                    timer = nextDelay();
                    return Place.BUSY;
                }
                // Hotbar full: SWAP the item (from the main inventory) into a hotbar slot, displacing a
                // target block or a spare shulker/ender chest — which just moves to the item's old main slot.
                // Nothing is dropped (so it can't loop on re-pickup), nothing is lost, and tools/food are
                // never displaced. Works no matter how full the inventory is.
                int srcMain = mainSlotMatching(pred);
                if (srcMain == -1) {
                    placeFail = "item isn't in the main inventory to stage";
                    return Place.FAILED;
                }
                int hbSlot = hotbarSwapSlot();
                if (hbSlot == -1) {
                    placeFail = "hotbar is all tools/food — no slot to stage into";
                    return Place.FAILED;
                }
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, srcMain, hbSlot, SlotActionType.SWAP, mc.player);
                attempts = 0;
                step = 2;
                timer = nextDelay();
                return Place.BUSY;
            }
            case 2 -> { // wait for the move/swap to land
                if (InvUtils.findInHotbar(pred).found()) {
                    step = 3;
                    attempts = 0;
                } else if (++attempts >= 5) {
                    placeFail = "item never reached the hotbar";
                    return Place.FAILED;
                }
                timer = nextDelay();
                return Place.BUSY;
            }
            default -> { // 3: place from the hotbar (BlockUtils.place selects + places + restores)
                FindItemResult hb = InvUtils.findInHotbar(pred);
                if (hb.found() && BlockUtils.place(pendingPlace, hb, true, 0, true)) {
                    timer = nextDelay();
                    return Place.DONE;
                }
                if (++attempts >= 4) { // this spot won't take the block — blacklist it and try another
                    placeTried.add(pendingPlace);
                    BlockPos next = findPlacementSpot(placeTried);
                    if (next == null) {
                        placeFail = "couldn't place at any open spot near the bot";
                        return Place.FAILED;
                    }
                    dbg("place failed at %s — trying spot %s", pendingPlace.toShortString(), next.toShortString());
                    pendingPlace = next;
                    attempts = 0;
                }
                timer = nextDelay();
                return Place.BUSY;
            }
        }
    }

    // ---------------- Baritone control ----------------

    private void startMining() {
        startClearArea(); // stock Baritone BuilderProcess quarry of the current chunk box
    }

    private void stopMining() {
        if (baritone != null) {
            baritone.getMineProcess().cancel();        // legacy safety (no-op if unused)
            baritone.getBuilderProcess().onLostControl(); // cancel any active clearArea quarry
            baritone.getPathingBehavior().cancelEverything();
        }
        // A pause (hazard/hunger/explicit) can preempt a break mid-swing and the cycle resumes from MINING,
        // so clear the break/pickup tracker — the next cycle's break must re-snapshot, not reuse a stale one.
        pickupSnapshotTaken = false;
        pickupBreakTicks = 0;
    }

    // ----- Outward chunk spiral -----

    /**
     * Work out the mining bounds for this run, for the non-corner cases. Unbounded =>
     * areaLimited=false (infinite spiral from the activation chunk). ChunksFromStart => a
     * width x length chunk box centred on the activation chunk, Y from min-y-level up to the
     * activation level. CornerSelect resolves its own bounds in {@link #resolveAreaFromCorners()}.
     * Either way the quarry ceiling is captured from the player's current Y so the box never
     * chases the player downward.
     */
    private void resolveArea() {
        BlockPos fp = mc.player.getBlockPos();
        int startChunkCX = fp.getX() >> 4;
        int startChunkCZ = fp.getZ() >> 4;
        quarryTopY = fp.getY() + 1;

        if (limitArea.get() && areaMode.get() == AreaMode.ChunksFromStart) {
            areaLimited = true;
            int w = areaWidthChunks.get(), l = areaLengthChunks.get();
            if (areaAnchor.get() == AreaAnchor.Corner) {
                // Activation chunk is a corner; extend the box in the player's horizontal facing.
                int[] dir = facingExtend();
                if (dir[0] >= 0) {
                    gridCxMin = startChunkCX;
                    gridCxMax = startChunkCX + w - 1;
                } else {
                    gridCxMax = startChunkCX;
                    gridCxMin = startChunkCX - (w - 1);
                }
                if (dir[1] >= 0) {
                    gridCzMin = startChunkCZ;
                    gridCzMax = startChunkCZ + l - 1;
                } else {
                    gridCzMax = startChunkCZ;
                    gridCzMin = startChunkCZ - (l - 1);
                }
            } else { // Center: activation chunk in the middle of the box
                gridCxMin = startChunkCX - (w - 1) / 2;
                gridCxMax = gridCxMin + w - 1;
                gridCzMin = startChunkCZ - (l - 1) / 2;
                gridCzMax = gridCzMin + l - 1;
            }
            areaMinX = gridCxMin << 4;
            areaMaxX = (gridCxMax << 4) + 15;
            areaMinZ = gridCzMin << 4;
            areaMaxZ = (gridCzMax << 4) + 15;
            areaMinY = minYLevel.get();
            areaMaxY = quarryTopY;
            startCX = (gridCxMin + gridCxMax) >> 1; // spiral centre = grid centre (Baritone paths there)
            startCZ = (gridCzMin + gridCzMax) >> 1;
        } else {
            areaLimited = false;
            areaMinY = minYLevel.get();
            areaMaxY = quarryTopY; // for the debug block counter only
            startCX = startChunkCX;
            startCZ = startChunkCZ;
        }
        dbg("resolveArea: limited=%b mode=%s Y[%d..%d] spiralCenter[%d,%d]%s",
            areaLimited, areaMode.get(), areaMinY, areaMaxY, startCX, startCZ,
            areaLimited ? String.format(" grid x[%d..%d] z[%d..%d]", gridCxMin, gridCxMax, gridCzMin, gridCzMax) : "");
    }

    /**
     * CornerSelect: turn the two marked corners into the bounded box + spiral centre.
     */
    private void resolveAreaFromCorners() {
        areaLimited = true;
        areaMinX = Math.min(corner1.getX(), corner2.getX());
        areaMaxX = Math.max(corner1.getX(), corner2.getX());
        areaMinZ = Math.min(corner1.getZ(), corner2.getZ());
        areaMaxZ = Math.max(corner1.getZ(), corner2.getZ());
        int yLo = Math.min(corner1.getY(), corner2.getY());
        int yHi = Math.max(corner1.getY(), corner2.getY());
        areaMinY = Math.max(yLo, minYLevel.get()); // never below the bedrock-floor guard
        areaMaxY = yHi;
        gridCxMin = areaMinX >> 4;
        gridCxMax = areaMaxX >> 4;
        gridCzMin = areaMinZ >> 4;
        gridCzMax = areaMaxZ >> 4;
        startCX = (gridCxMin + gridCxMax) >> 1; // spiral centre = middle chunk of the grid
        startCZ = (gridCzMin + gridCzMax) >> 1;
        dbg("resolveAreaFromCorners: box x[%d..%d] y[%d..%d] z[%d..%d] grid x[%d..%d] z[%d..%d] center[%d,%d]",
            areaMinX, areaMaxX, areaMinY, areaMaxY, areaMinZ, areaMaxZ, gridCxMin, gridCxMax, gridCzMin, gridCzMax, startCX, startCZ);
    }

    // ---------------- Pre-mining resource scan (HUD) ----------------

    /**
     * Scan the mining area ONCE (at activation, before mining) and publish the most-abundant blocks/ores
     * to {@link ScanData} for the Resource Scan HUD. The footprint is EXACTLY the area being mined: for a
     * limited area it's the resolved box [areaMin..areaMax]; for the unbounded spiral it's just the chunk
     * the run starts in (there's no finite area to total). The vertical span is the mining band
     * [floor..maxY] — never above the area's maxY. Only loaded chunks are read (we can't see ungenerated
     * ones client-side), and a block budget caps the work so a huge area can't freeze the game.
     */
    private void runChunkScan() {
        if (mc.world == null) return;
        int topY = areaLimited ? areaMaxY : quarryTopY;        // never higher than the area's maxY
        int botY = areaLimited ? areaMinY : minYLevel.get();
        int x1, z1, x2, z2;
        if (areaLimited) {
            x1 = areaMinX;
            z1 = areaMinZ;
            x2 = areaMaxX;
            z2 = areaMaxZ;
        } else {
            int bx = startCX << 4, bz = startCZ << 4;
            x1 = bx;
            z1 = bz;
            x2 = bx + 15;
            z2 = bz + 15;
        }

        java.util.HashMap<Block, Integer> counts = new java.util.HashMap<>();
        int chunksScanned = 0;
        long budget = 0;
        final long BUDGET_MAX = 16_000_000L;                   // hard cap on blocks visited (anti-freeze)
        BlockPos.Mutable p = new BlockPos.Mutable();

        for (int cx = (x1 >> 4); cx <= (x2 >> 4); cx++) {
            for (int cz = (z1 >> 4); cz <= (z2 >> 4); cz++) {
                var chunk = mc.world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;                   // not loaded — can't read it client-side
                chunksScanned++;
                int bxMin = Math.max(x1, cx << 4), bxMax = Math.min(x2, (cx << 4) + 15);
                int bzMin = Math.max(z1, cz << 4), bzMax = Math.min(z2, (cz << 4) + 15);
                for (int bx = bxMin; bx <= bxMax; bx++) {
                    for (int bz = bzMin; bz <= bzMax; bz++) {
                        for (int by = botY; by <= topY; by++) {
                            if (++budget > BUDGET_MAX) {
                                publishScan(counts, chunksScanned, botY, topY);
                                return;
                            }
                            p.set(bx, by, bz);
                            var st = chunk.getBlockState(p);
                            if (st.isAir()) continue;          // ignore air (it dominates a quarry otherwise)
                            Block b = st.getBlock();
                            if (b == Blocks.WATER || b == Blocks.LAVA) {
                                if (!st.getFluidState().isStill()) continue; // count only SOURCE fluids, skip flowing
                            }
                            counts.merge(b, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        publishScan(counts, chunksScanned, botY, topY);
    }

    /**
     * Rank the tallied blocks into the top 3 non-ore blocks + top 5 ores and hand them to the HUD.
     */
    private void publishScan(java.util.Map<Block, Integer> counts, int chunks, int botY, int topY) {
        List<java.util.Map.Entry<Block, Integer>> blockE = new java.util.ArrayList<>();
        List<java.util.Map.Entry<Block, Integer>> oreE = new java.util.ArrayList<>();
        for (var e : counts.entrySet()) (isOreBlock(e.getKey()) ? oreE : blockE).add(e);
        java.util.Comparator<java.util.Map.Entry<Block, Integer>> byCountDesc =
            (a, b) -> Integer.compare(b.getValue(), a.getValue());
        blockE.sort(byCountDesc);
        oreE.sort(byCountDesc);

        List<ScanData.Count> blocks = new java.util.ArrayList<>();
        List<ScanData.Count> ores = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(3, blockE.size()); i++) blocks.add(toCount(blockE.get(i)));
        for (int i = 0; i < Math.min(5, oreE.size()); i++) ores.add(toCount(oreE.get(i)));

        ScanData.topBlocks = blocks;
        ScanData.topOres = ores;
        ScanData.summary = String.format("%d chunk%s · y%d..%d", chunks, chunks == 1 ? "" : "s", botY, topY);
        ScanData.valid = true;
        dbg("chunk scan: %d chunks, y[%d..%d], %d block types (%d ore types)", chunks, botY, topY, counts.size(), oreE.size());
        info("预扫描完成 (%d chunk%s) — 查看“资源扫描”HUD 元素.", chunks, chunks == 1 ? "" : "s");
    }

    private ScanData.Count toCount(java.util.Map.Entry<Block, Integer> e) {
        return new ScanData.Count(e.getKey().getName().getString(), e.getValue());
    }

    /**
     * An ore for the scan: any "*_ore" block (coal/iron/.../deepslate & nether variants), plus ancient debris.
     */
    private boolean isOreBlock(Block b) {
        if (b == Blocks.ANCIENT_DEBRIS) return true;
        return Registries.BLOCK.getId(b).getPath().contains("_ore");
    }

    /**
     * Reset the outward-square spiral stepper to the centre (startCX/startCZ) and clear the per-pass
     * clear-area cursor. Called at the start of the run AND at the start of each new horizontal layer,
     * so every layer re-sweeps the whole area's chunks from the centre out.
     */
    private void resetSpiralStepper() {
        spX = 0;
        spZ = 0;
        spDir = 0;
        spSegLen = 1;
        spSegLeft = 1;
        spSegDone = 0;
        curCX = startCX;
        curCZ = startCZ;
        subBoxes.clear();
        subBoxIdx = 0;
        areaChunksDone = 0;
    }

    /**
     * Seed the whole run: reset the spiral, the stall watch, and start at the TOP layer (descends down).
     */
    private void seedSpiral() {
        resetSpiralStepper();
        clearAreaStarted = false;
        clearAreaStallTicks = 0;
        lastClearPos = null;
        unstickPos = null;
        unstickTicks = 0;
        unstickBlocksDone = 0;
        subBoxRetries = 0;
        areaComplete = false;
        curLayerTopY = areaMaxY;               // bounded: sweep from the top layer downward
        areaChunksTotal = areaLimited
            ? (gridCxMax - gridCxMin + 1) * (gridCzMax - gridCzMin + 1)
            : 0;
        dbg("seedSpiral: center[%d,%d] areaChunksTotal=%d topLayerY=%d layerH=%d", startCX, startCZ, areaChunksTotal, curLayerTopY, layerThickness());
    }

    /**
     * Vertical thickness of each top-down horizontal layer (bounded areas), clamped to >= 1.
     */
    private int layerThickness() {
        return Math.max(1, areaLayerHeight.get());
    }

    /**
     * Y floor of the layer currently being swept (bounded), clamped to the area floor.
     */
    private int curLayerBottomY() {
        return Math.max(areaMinY, curLayerTopY - layerThickness() + 1);
    }

    /**
     * Sign (+1/-1) of the player's horizontal facing on each axis {x, z}, used by the Corner anchor to
     * extend the box in the direction the player is looking. Derived from yaw only (ignores pitch, so
     * looking up/down doesn't collapse the direction). East = +X, South = +Z.
     */
    private int[] facingExtend() {
        double yaw = Math.toRadians(mc.player.getYaw());
        double lx = -Math.sin(yaw);
        double lz = Math.cos(yaw);
        return new int[]{lx >= 0 ? 1 : -1, lz >= 0 ? 1 : -1};
    }

    // ----- ClearArea engine: stock BuilderProcess quarry of one chunk box at a time -----

    /**
     * Issue a clearArea for the current spiral chunk. Unbounded: the whole chunk box, Y floor..quarry
     * ceiling. Bounded: the chunk box CLAMPED to the area bounds, so an edge chunk never breaks a block
     * outside your box (and Y spans the area's own floor..ceiling).
     */
    private void startClearArea() {
        int bx = curCX << 4, bz = curCZ << 4;
        int x1 = bx, x2 = bx + 15, z1 = bz, z2 = bz + 15;
        if (areaLimited) {
            x1 = Math.max(x1, areaMinX);
            x2 = Math.min(x2, areaMaxX);
            z1 = Math.max(z1, areaMinZ);
            z2 = Math.min(z2, areaMaxZ);
        }
        buildSubBoxes(x1, z1, x2, z2);
        subBoxIdx = 0;
        subBoxRetries = 0;
        issueSubBox();
        clearAreaStarted = true;
    }

    /**
     * Divide a chunk's clamped XZ footprint into clear-box-size × clear-box-size cells (row-major).
     */
    private void buildSubBoxes(int x1, int z1, int x2, int z2) {
        subBoxes.clear();
        int s = Math.max(1, clearBoxSize.get());
        for (int sx = x1; sx <= x2; sx += s) {
            for (int sz = z1; sz <= z2; sz += s) {
                subBoxes.add(new int[]{sx, sz, Math.min(sx + s - 1, x2), Math.min(sz + s - 1, z2)});
            }
        }
        if (subBoxes.isEmpty()) subBoxes.add(new int[]{x1, z1, x2, z2}); // safety (degenerate bounds)
    }

    /**
     * Issue clearArea for the current sub-box, and reset the stall watch. Bounded areas mine ONE
     * horizontal layer at a time (the [curLayerBottomY..curLayerTopY] slice), so the whole area's top
     * is swept before descending — the unbounded spiral still clears the full Y band per chunk.
     */
    private void issueSubBox() {
        int y1 = areaLimited ? curLayerBottomY() : minYLevel.get();
        int y2 = areaLimited ? curLayerTopY : quarryTopY;
        int[] b = subBoxes.get(subBoxIdx);
        callClearArea(new BlockPos(b[0], y1, b[1]), new BlockPos(b[2], y2, b[3]));
        clearAreaStallTicks = 0;
        lastClearPos = mc.player.getBlockPos();
        dbg("clearArea chunk [%d,%d] sub-box %d/%d x[%d..%d] z[%d..%d] y[%d..%d]",
            curCX, curCZ, subBoxIdx + 1, subBoxes.size(), b[0], b[2], b[1], b[3], y1, y2);
    }

    /**
     * Drive the ClearArea engine: when the builder finishes a chunk box it goes inactive — advance
     * the spiral and start the next. While it's working, watch for a genuine stall (bot hasn't moved
     * for a while = an unreachable pocket) and skip the chunk so it can never hang.
     */
    private void tickClearAreaMining() {
        if (!baritone.getBuilderProcess().isActive()) {
            if (clearAreaStarted) {
                if (retrySubBoxIfDirty(false))
                    return;   // lag spike left blocks standing -> re-run the box before advancing
                beginCollect(false);          // sub-box cleared; vacuum its drops, then next sub-box / chunk
            } else {
                startClearArea();             // very first chunk of the run
                timer = nextDelay();
            }
            return;
        }
        // builder is working — detect a true stall via lack of movement (not just slow progress)
        BlockPos pos = mc.player.getBlockPos();
        if (lastClearPos == null || !pos.equals(lastClearPos)) {
            lastClearPos = pos;
            clearAreaStallTicks = 0;
            unstickBlocksDone = 0;            // the bot moved = progress; reset the assist budget
        } else if (++clearAreaStallTicks > CLEARAREA_STALL_TICKS) {
            if (retrySubBoxIfDirty(true))
                return;        // stalled, but blocks remain & retries left -> retry instead of skipping
            warning("clearArea stalled on chunk [%d, %d] sub-box %d/%d (no movement %ds) — skipping.",
                curCX, curCZ, subBoxIdx + 1, subBoxes.size(), CLEARAREA_STALL_TICKS / 20);
            dbg("clearArea stall: skipping chunk [%d,%d] sub-box %d", curCX, curCZ, subBoxIdx + 1);
            baritone.getBuilderProcess().onLostControl();
            afterSubBox(true);
        } else if (clearAreaStallTicks == UNSTICK_STALL_TICKS && unstickBlocksDone < UNSTICK_MAX_BLOCKS) {
            // 3s stalled and we still have assist budget: Baritone is re-issuing START on a block it can't
            // finish (fence/edge block at low reach). Take over and HOLD the break ourselves before the 10s skip.
            BlockPos stuck = findStuckBlock();
            if (stuck != null) {
                dbg("clearArea stall %ds on [%d,%d] — manual-break assist on %s", UNSTICK_STALL_TICKS / 20, curCX, curCZ, stuck.toShortString());
                baritone.getBuilderProcess().onLostControl();
                baritone.getPathingBehavior().cancelEverything();
                unstickPos = stuck;
                unstickTicks = 0;
                go(State.UNSTICK);
            } else {
                dbg("clearArea stall assist: no breakable block in reach of the bot — waiting for the skip timer");
            }
        }
    }

    // ----- UNSTICK: hold a break on the block Baritone stalled on, then hand the sub-box back -----

    /**
     * Drive the manual-break assist one tick. Hold a continuous break on {@link #unstickPos} (rotate +
     * {@link BlockUtils#breakBlock} every tick — one START, progress accumulates, STOP on completion), which
     * isn't subject to the lowered client crosshair reach the way Baritone's mining is. On success (or after
     * a per-block timeout) resume clearArea on the same sub-box; if the assist can't make progress (no block,
     * or budget spent), fall back to the original sub-box skip so the run can never hang.
     */
    private void tickUnstick() {
        if (unstickPos == null) {
            resumeFromUnstick(false);
            return;
        }
        var st = mc.world.getBlockState(unstickPos);
        if (st.isReplaceable() || st.getBlock() == Blocks.BEDROCK) {   // broken (or already gone) — success
            dbg("unstick: broke %s — resuming mining", unstickPos.toShortString());
            unstickBlocksDone++;
            resumeFromUnstick(true);
            return;
        }
        if (++unstickTicks > UNSTICK_BREAK_TICKS) {                    // couldn't break it in time
            dbg("unstick: couldn't break %s in %ds — giving up the assist", unstickPos.toShortString(), UNSTICK_BREAK_TICKS / 20);
            resumeFromUnstick(false);
            return;
        }
        BlockUtils.breakBlock(unstickPos, true); // continuous held break — runs every tick, no action-delay
    }

    /**
     * Leave the assist: re-issue the current sub-box to Baritone (on a successful break, with budget left) or
     * fall back to skipping the sub-box (assist failed, or too many assists without the bot moving).
     */
    private void resumeFromUnstick(boolean broke) {
        unstickPos = null;
        unstickTicks = 0;
        go(State.MINING);
        if (broke && unstickBlocksDone < UNSTICK_MAX_BLOCKS) {
            issueSubBox();                       // resume clearArea on the same sub-box (it may now get past the spot)
            timer = nextDelay();
        } else {
            unstickBlocksDone = 0;
            if (baritone != null) baritone.getBuilderProcess().onLostControl();
            afterSubBox(true);                   // original behaviour: skip this sub-box so the run can't hang
        }
    }

    /**
     * The block to hand the manual-break assist: the nearest EXPOSED, breakable, non-bedrock block inside the
     * current sub-box that's within vanilla interaction range (~4.5) of the bot's eyes — i.e. the block
     * Baritone is almost certainly stalled on. Never the block under the bot's feet (don't drop the floor),
     * never below the Y floor. Returns null if nothing reachable (then the normal skip timer handles it).
     */
    private BlockPos findStuckBlock() {
        if (subBoxes.isEmpty() || subBoxIdx >= subBoxes.size()) return null;
        int[] b = subBoxes.get(subBoxIdx);
        BlockPos feet = mc.player.getBlockPos();
        int yLo = Math.max(areaLimited ? curLayerBottomY() : minYLevel.get(), feet.getY() - 5);
        int yHi = Math.min(areaLimited ? curLayerTopY : quarryTopY, feet.getY() + 5);
        Vec3d eye = mc.player.getEyePos();
        double bestSq = UNSTICK_REACH * UNSTICK_REACH;
        BlockPos best = null;
        for (int x = b[0]; x <= b[2]; x++) {
            for (int z = b[1]; z <= b[3]; z++) {
                for (int y = yHi; y >= yLo; y--) {
                    if (y < minYLevel.get()) continue;
                    BlockPos p = new BlockPos(x, y, z);
                    if (p.equals(feet) || p.equals(feet.down())) continue;        // never break the block we stand on
                    var st = mc.world.getBlockState(p);
                    if (st.isReplaceable() || st.getBlock() == Blocks.BEDROCK) continue;
                    if (!isExposed(p))
                        continue;                                  // must touch air to be mineable from here
                    double d = eye.squaredDistanceTo(Vec3d.ofCenter(p));
                    if (d <= bestSq) {
                        bestSq = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    /**
     * A sub-box finished (cleared) or was abandoned (stalled). Move to the next sub-box in this chunk,
     * or — once the chunk's last sub-box is done — hand off to {@link #afterChunk(boolean)} to count the
     * chunk and advance the spiral.
     */
    /**
     * On a sub-box finishing or stalling, verify the quarry actually cleared it. A lag spike can make
     * clearArea report a box done (or give up on a stall) with solid blocks still standing. If leftovers
     * remain and the per-box retry budget isn't spent, re-run the SAME sub-box and return true (the caller
     * should return — we've re-issued it). Otherwise reset the budget and return false so the caller
     * advances/collects/skips as normal. Gated by 'verify-clears'.
     */
    private boolean retrySubBoxIfDirty(boolean stalled) {
        if (!verifyClears.get()) return false;
        if (subBoxRetries >= clearRetries.get() || !subBoxHasLeftovers()) {
            subBoxRetries = 0;
            return false;
        }
        subBoxRetries++;
        if (stalled && baritone != null) baritone.getBuilderProcess().onLostControl();
        info("Sub-box not fully cleared (%s) — retrying it (%d/%d).", stalled ? "stalled" : "lag", subBoxRetries, clearRetries.get());
        dbg("verify: chunk [%d,%d] sub-box %d/%d still dirty (%s) — retry %d/%d",
            curCX, curCZ, subBoxIdx + 1, subBoxes.size(), stalled ? "stalled" : "done-early", subBoxRetries, clearRetries.get());
        issueSubBox();   // re-run clearArea on the same sub-box (resets the stall watch + lastClearPos)
        timer = nextDelay();
        return true;
    }

    /**
     * True if the current sub-box still holds a solid, breakable block the quarry should have removed.
     * Scans the box's XZ over the active Y band, ignoring air/replaceable blocks, bedrock, and the two
     * blocks under the bot (the floor it stands on, which clearArea breaks last from an adjacent spot).
     * Returns on the first leftover found; cost-capped so a very tall/large box can't stall the tick.
     */
    private boolean subBoxHasLeftovers() {
        if (subBoxes.isEmpty() || subBoxIdx >= subBoxes.size()) return false;
        int[] b = subBoxes.get(subBoxIdx);
        int y1 = areaLimited ? curLayerBottomY() : minYLevel.get();
        int y2 = areaLimited ? curLayerTopY : quarryTopY;
        BlockPos feet = mc.player.getBlockPos();
        int scanned = 0;
        for (int y = y2; y >= y1; y--) {
            for (int x = b[0]; x <= b[2]; x++) {
                for (int z = b[1]; z <= b[3]; z++) {
                    if (++scanned > 8192) return false;                  // cost cap — assume clear if the box is huge
                    if (y < minYLevel.get()) continue;
                    BlockPos p = new BlockPos(x, y, z);
                    if (p.equals(feet) || p.equals(feet.down())) continue;   // the floor under the bot
                    var st = mc.world.getBlockState(p);
                    if (st.isAir() || st.isReplaceable() || st.getBlock() == Blocks.BEDROCK) continue;
                    return true;
                }
            }
        }
        return false;
    }

    private void afterSubBox(boolean stalled) {
        if (subBoxIdx + 1 < subBoxes.size()) {
            subBoxIdx++;
            subBoxRetries = 0;            // fresh budget for the next sub-box
            issueSubBox();
            timer = nextDelay();
        } else {
            afterChunk(stalled);
        }
    }

    // ----- COLLECT: vacuum the just-cleared sub-box's drops before moving on -----

    /**
     * A sub-box just cleared. If drop-collection is on and there's a kept item lying in it (and room to
     * hold more), enter the COLLECT state to walk over them; otherwise advance straight away. The
     * sub-box's XZ footprint is snapshotted so the bounds stay correct even though subBoxIdx hasn't moved.
     */
    private void beginCollect(boolean stalled) {
        if (!collectDrops.get() || !canHoldMoreTarget()) {
            afterSubBox(stalled);
            return;
        }
        int[] b = subBoxes.get(subBoxIdx);
        collectBounds = new int[]{b[0], b[1], b[2], b[3]};
        collectStalled = stalled;
        collectTarget = null;
        collectPathed = false;
        collectSettleTicks = 0;
        collectTargetTicks = 0;
        collectTotalTicks = 0;
        collectSkip.clear();
        if (nearestCollectable() == null) {
            afterSubBox(stalled);
            return;
        } // nothing dropped here
        baritone.getBuilderProcess().onLostControl();    // builder's done with this box; release it
        dbg("collect: vacuuming drops in sub-box x[%d..%d] z[%d..%d]", b[0], b[2], b[1], b[3]);
        go(State.COLLECT);
        timer = nextDelay();
    }

    private void tickCollect() {
        // No room to pick anything up -> stop; the next mining tick fires the storage cycle.
        if (!canHoldMoreTarget()) {
            dbg("collect: inventory full — done");
            finishCollect();
            return;
        }
        // Overall cap: a few drops landed somewhere unreachable; don't linger forever.
        if (++collectTotalTicks > collectMaxSeconds.get() * 20) {
            dbg("collect: hit %ds cap — moving on", collectMaxSeconds.get());
            finishCollect();
            return;
        }
        // Working a live drop?
        if (collectTarget != null && collectTarget.isAlive() && !collectTarget.isRemoved()) {
            if (!collectPathed) {
                // Settle delay: let a freshly-dropped item come to rest before chasing it, so the bot
                // doesn't path to a stale block while it's still bouncing/rolling. Path once it's on the
                // ground (or the settle window elapses), reading its CURRENT block so we go to where it
                // actually landed.
                if (!collectTarget.isOnGround() && ++collectSettleTicks < COLLECT_SETTLE_TICKS) return;
                pathToBlock(collectTarget.getBlockPos()); // stand ON the drop (2b won't always grab from a block away)
                collectPathed = true;
                collectTargetTicks = 0;
                timer = nextDelay();
                return;
            }
            if (++collectTargetTicks > COLLECT_ITEM_TICKS) {
                dbg("collect: gave up on drop %d (unreachable)", collectTarget.getId());
                collectSkip.add(collectTarget.getId());
                collectTarget = null;                    // fall through and pick another
            } else {
                return;                                  // heading to it
            }
        } else {
            collectTarget = null;                        // picked up / despawned
        }
        // Pick the nearest remaining drop; the settle branch above paths to it on the next tick.
        ItemEntity next = nearestCollectable();
        if (next == null) {
            dbg("collect: all drops grabbed");
            finishCollect();
            return;
        }
        collectTarget = next;
        collectPathed = false;
        collectSettleTicks = 0;
        collectTargetTicks = 0;
        timer = nextDelay();
    }

    /**
     * Nearest kept-item drop still on the ground in (or just around) the sub-box we're vacuuming.
     */
    private ItemEntity nearestCollectable() {
        if (collectBounds == null) return null;
        ItemEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (ItemEntity e : mc.world.getEntitiesByClass(ItemEntity.class, collectArea(),
            e -> e.isAlive() && !collectSkip.contains(e.getId()) && isTargetStack(e.getStack()))) {
            double d = e.squaredDistanceTo(mc.player);
            if (d < bestSq) {
                bestSq = d;
                best = e;
            }
        }
        return best;
    }

    /**
     * Nearest dropped item (matching p) within a few blocks of where we just broke it (it can bounce off).
     */
    private ItemEntity nearestDrop(BlockPos near, java.util.function.Predicate<ItemStack> p) {
        if (near == null || mc.world == null) return null;
        ItemEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (ItemEntity e : mc.world.getEntitiesByClass(ItemEntity.class, new Box(near).expand(4),
            e -> e.isAlive() && p.test(e.getStack()))) {
            double d = e.squaredDistanceTo(mc.player);
            if (d < bestSq) {
                bestSq = d;
                best = e;
            }
        }
        return best;
    }

    private enum Pickup {BUSY, DONE, GAVE_UP}

    /**
     * Reset the shared pickup tracker before a break, snapshotting how many matching items we hold.
     */
    private void beginPickup(java.util.function.Predicate<ItemStack> match) {
        pickupDrop = null;
        pickupPathed = false;
        pickupSettleTicks = 0;
        pickupGoal = null;
        pickupAttempts = 0;
        pickupBefore = countItemsMatching(match); // ITEM count, not slots — a recovered echest re-stacks
    }

    /**
     * Drive the shared shulker-drop pickup one tick. {@code breakPos} is where the shulker was broken,
     * {@code match} matches both the dropped item and the inventory stack. Returns DONE when the inventory
     * count rose (collected), GAVE_UP after ~10s (proceed anyway — the store will no-op if it's truly gone),
     * or BUSY while settling/chasing. Locates the actual drop and re-paths onto it as it drifts, so a
     * shulker that bounced a block off the break spot is still walked onto.
     */
    private Pickup tickPickupDrop(BlockPos breakPos, java.util.function.Predicate<ItemStack> match) {
        if (countItemsMatching(match) > pickupBefore) {
            if (baritone != null) baritone.getPathingBehavior().cancelEverything(); // stop the nudge walk
            pickupDrop = null;
            pickupGoal = null;
            return Pickup.DONE;
        }
        if (++pickupAttempts > 20 * 10) {
            if (baritone != null) baritone.getPathingBehavior().cancelEverything();
            pickupDrop = null;
            pickupGoal = null;
            return Pickup.GAVE_UP;
        }
        // (Re)acquire the dropped shulker item — it can pop a block off the break spot.
        if (pickupDrop == null || !pickupDrop.isAlive() || pickupDrop.isRemoved()) {
            pickupDrop = nearestDrop(breakPos, match);
            pickupPathed = false;
            pickupSettleTicks = 0;
            pickupGoal = null;
        }
        if (pickupDrop != null) {
            // Settle delay: let the freshly-broken shulker land before chasing, so we path to where it
            // actually lands (it may bounce a block over) instead of a stale spot.
            if (!pickupDrop.isOnGround() && ++pickupSettleTicks < COLLECT_SETTLE_TICKS) return Pickup.BUSY;
            // Re-path whenever the drop has drifted to a new block, so a bounce a block away is chased.
            BlockPos cur = pickupDrop.getBlockPos();
            if (!pickupPathed || !cur.equals(pickupGoal)) {
                pathToBlock(cur);              // stand ON the drop (2b won't always grab it from a block away)
                pickupGoal = cur;
                pickupPathed = true;
            }
        } else if (!pickupPathed && breakPos != null) {
            pathToBlock(breakPos);
            pickupPathed = true; // no entity in range yet — walk to the break spot
        }
        return Pickup.BUSY;
    }

    private boolean isEnderChest(ItemStack s) {
        return s.getItem() == Items.ENDER_CHEST;
    }

    /**
     * Shared "break the placed ender chest, then collect its drop" step, used by every echest-break site
     * (storage recover, deposit extract/stock, restock recover). On 2b a broken block can fly up to ~2
     * blocks, lag delays settling, and ghost blocks happen — so this breaks the chest (with a ghost-block
     * cap so a stale client block can't spin forever), then chases the dropped ender chest onto the bot
     * before reporting done. Returns {@code false} while still breaking/chasing (the caller should return
     * and tick again — timing is handled here), {@code true} once collected (or the ~10s give-up elapsed).
     * Clears {@code placedEchest} on completion.
     */
    private boolean breakAndCollectEchest() {
        if (placedEchest != null && mc.world.getBlockState(placedEchest).getBlock() == Blocks.ENDER_CHEST) {
            if (!pickupSnapshotTaken) {
                beginPickup(this::isEnderChest);
                pickupSnapshotTaken = true;
                pickupBreakTicks = 0;
            }
            if (++pickupBreakTicks <= 20 * 10) {                 // still swinging (cap ~10s for ghost blocks)
                BlockUtils.breakBlock(placedEchest, true);       // continuous until it pops — runs every tick
                return false;
            }
            dbg("echest break: block still reads as ender chest after 10s — treating as ghost, moving to pickup");
        }
        if (pickupSnapshotTaken) { // we placed/broke a chest this visit — chase the drop before proceeding
            Pickup r = tickPickupDrop(placedEchest, this::isEnderChest);
            if (r == Pickup.BUSY) {
                timer = nextDelay();
                return false;
            }
            if (r == Pickup.GAVE_UP) dbg("echest pickup: gave up chasing the ender chest drop — proceeding");
            pickupSnapshotTaken = false;
        }
        placedEchest = null;
        return true;
    }

    /**
     * The sub-box XZ footprint (with a small margin) over the active Y band — where drops can rest.
     */
    private Box collectArea() {
        int yLo = (areaLimited ? curLayerBottomY() : minYLevel.get()) - 2;
        int yHi = (areaLimited ? curLayerTopY : quarryTopY) + 2;
        return new Box(collectBounds[0] - 2, yLo, collectBounds[1] - 2,
            collectBounds[2] + 3, yHi, collectBounds[3] + 3);
    }

    /**
     * Done vacuuming this sub-box: drop the walk goal and advance the quarry.
     */
    private void finishCollect() {
        baritone.getCustomGoalProcess().onLostControl();  // stop walking to drops
        collectTarget = null;
        collectBounds = null;
        go(State.MINING);                                 // afterSubBox issues the next box; MINING drives it
        afterSubBox(collectStalled);
    }

    /**
     * The current chunk box is finished (cleared) or abandoned (stalled). For a bounded area, count it
     * toward the CURRENT LAYER; once every chunk in the layer is done, drop one layer and re-sweep the
     * whole area (or finish the run once we've cleared down to areaMinY). For the infinite spiral it just
     * advances to the next chunk (full-height). Returns true if it routed to area-complete.
     */
    private boolean afterChunk(boolean stalled) {
        if (debugLog.get()) {
            int[] c = countMineBlocksInChunk(curCX, curCZ);
            dbg("chunk [%d,%d] done (%s): %d target block(s) left in box, %d exposed",
                curCX, curCZ, stalled ? "stalled" : "cleared", c[0], c[1]);
        }
        if (areaLimited && ++areaChunksDone >= areaChunksTotal) {
            // Whole-area horizontal layer cleared. Drop to the next layer and re-sweep, or — once the
            // next layer would start below the area floor — the area is fully mined.
            if (curLayerBottomY() <= areaMinY) {
                onAreaComplete();
                return true;
            }
            curLayerTopY -= layerThickness();
            resetSpiralStepper();                              // re-sweep every chunk at the new, lower layer
            info("Layer cleared — dropping to y[%d..%d] and sweeping the area.", curLayerBottomY(), curLayerTopY);
            startClearArea();
            timer = nextDelay();
            return false;
        }
        advanceSpiral();
        info(stalled ? "Skipped chunk — moving to chunk [%d, %d]." : "Chunk cleared — moving to chunk [%d, %d].", curCX, curCZ);
        startClearArea();
        timer = nextDelay();
        return false;
    }

    /**
     * Every in-box chunk has been cleared/skipped: store any remaining yield, then finish the run.
     */
    private void onAreaComplete() {
        areaComplete = true;
        stopMining();
        dbg("custom area fully cleared (%d chunks)", areaChunksTotal);
        if (hasTargetStacks() && countItem(Items.ENDER_CHEST) > 0) {
            info("Area cleared — storing the last of the haul.");
            go(State.CLEAR_AREA);             // final storage cycle; resumeMining() then routes to DONE
        } else {
            doneReason = "custom area fully cleared";
            go(State.DONE);
        }
    }

    private boolean chunkInGrid(int cx, int cz) {
        return cx >= gridCxMin && cx <= gridCxMax && cz >= gridCzMin && cz <= gridCzMax;
    }

    /**
     * Call IBuilderProcess.clearArea(BlockPos, BlockPos) reflectively. The addon is Yarn-mapped and
     * the Baritone API jar isn't, so we can't name its BlockPos parameter type at compile time — but
     * both BlockPos classes are identical at runtime after Fabric remapping, so reflection resolves
     * and invokes cleanly. (Same reason the addon drives MineProcess by name and pushes settings by
     * name.)
     */
    private void callClearArea(BlockPos c1, BlockPos c2) {
        try {
            Object builder = baritone.getBuilderProcess();
            builder.getClass().getMethod("clearArea", BlockPos.class, BlockPos.class).invoke(builder, c1, c2);
        } catch (Exception e) {
            warning("Couldn't start clearArea — is this Baritone build's BuilderProcess available?");
            dbg("clearArea reflection failed: %s", e.toString());
        }
    }

    /**
     * Step the spiral one chunk outward; the next clearArea() box is built from curCX/curCZ. When the
     * area is bounded, keep stepping past any chunk outside the grid so only in-box chunks are mined
     * (a guard cap stops a runaway if the grid math is ever off). The caller only advances while
     * in-box chunks remain, so an in-grid chunk is always found.
     */
    private void advanceSpiral() {
        if (areaLimited) {
            int guard = 0;
            do {
                stepSpiral();
            } while (!chunkInGrid(startCX + spX, startCZ + spZ) && ++guard < 100000);
        } else {
            stepSpiral();
        }
        curCX = startCX + spX;
        curCZ = startCZ + spZ;
    }

    /**
     * Outward square spiral over chunk offsets: (0,0),(1,0),(1,1),(0,1),(-1,1),(-1,0),(-1,-1)…
     * Directions cycle +x,+z,-x,-z; the segment length grows by one every two direction changes.
     */
    private void stepSpiral() {
        switch (spDir) {
            case 0 -> spX++;
            case 1 -> spZ++;
            case 2 -> spX--;
            default -> spZ--;
        }
        if (--spSegLeft == 0) {
            spDir = (spDir + 1) & 3;
            if (++spSegDone == 2) {
                spSegDone = 0;
                spSegLen++;
            }
            spSegLeft = spSegLen;
        }
    }

    // ---------------- Inventory helpers ----------------

    /**
     * Empty slots in the MAIN inventory only (indices 9-35); the hotbar is reserved for staging.
     */
    private int emptyMainSlots() {
        int n = 0;
        for (int i = 9; i <= 35; i++) if (mc.player.getInventory().getStack(i).isEmpty()) n++;
        return n;
    }

    /**
     * Can the MAIN inventory (9-35) accept even one more target block? True if there's an empty slot
     * (room for a new stack) or a target stack below 64 (room to top it off). When this is false the
     * inventory is completely packed — every target stack is a full 64 and no empty slots remain — so
     * a storage cycle will fill shulkers with whole stacks (the 'require-full-stacks' trigger).
     * Reserved non-target items (echest/shulker/tool/food) occupying a slot simply don't add capacity.
     */
    private boolean canHoldMoreTarget() {
        var inv = mc.player.getInventory();
        for (int i = 9; i <= 35; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) return true;
            if (isTargetStack(s) && s.getCount() < s.getMaxCount()) return true;
        }
        return false;
    }

    private boolean hasTargetStacks() {
        for (int i = 0; i < 36; i++) if (isTargetStack(mc.player.getInventory().getStack(i))) return true;
        return false;
    }

    /**
     * True if any hotbar slot (0-8) holds a target stack — overflow that sweepHotbarTargets() couldn't
     * relocate because the main inventory is full. Feeds the storage overflow guard in tickMining().
     */
    private boolean hotbarHasTarget() {
        var inv = mc.player.getInventory();
        for (int i = 0; i <= 8; i++) if (isTargetStack(inv.getStack(i))) return true;
        return false;
    }

    /**
     * A "target" for inventory purposes = a kept item (the mined DROP), not the world block mined.
     */
    private boolean isTargetStack(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return keepItems.get().contains(stack.getItem());
    }

    private int countItem(Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getStack(i).getItem() == item) n++;
        return n;
    }

    private boolean isEmptyShulkerStack(ItemStack stack) {
        return isShulker(stack) && !hasContents(stack);
    }

    private boolean isFilledShulkerStack(ItemStack stack) {
        return isShulker(stack) && hasContents(stack);
    }

    private boolean isShulker(ItemStack stack) {
        return !stack.isEmpty()
            && stack.getItem() instanceof BlockItem bi
            && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    /**
     * Container-contents detection for a shulker item in 1.21.4 uses the CONTAINER component.
     */
    private boolean hasContents(ItemStack stack) {
        var c = stack.get(DataComponentTypes.CONTAINER);
        return c != null && c.stream().findAny().isPresent();
    }

    private int countShulkers(java.util.function.Predicate<ItemStack> p) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.test(mc.player.getInventory().getStack(i))) n++;
        return n;
    }

    /**
     * Total ITEM COUNT (summed stack sizes), not slot count, of inventory stacks matching p. The shared
     * pickup success check must use this: a recovered ender chest re-stacks into an existing echest slot,
     * so the slot count (countShulkers) wouldn't change and the pickup would never register as done. (For
     * non-stackable drops like filled/tool shulkers this equals the slot count, so it's safe everywhere.)
     */
    private int countItemsMatching(java.util.function.Predicate<ItemStack> p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (p.test(s)) n += s.getCount();
        }
        return n;
    }

    private boolean isEmptyShulkerInInv(ItemStack s) {
        return isEmptyShulkerStack(s);
    }

    private boolean isFilledShulkerInInv(ItemStack s) {
        return isFilledShulkerStack(s);
    }

    private boolean isEmptyShulker(ItemStack s) {
        return isEmptyShulkerStack(s);
    }

    /**
     * A FILLED loot shulker (has contents) that is NOT the tool-shulker — i.e. mined haul to haul off.
     */
    private boolean isLootFilledShulker(ItemStack s) {
        return isFilledShulkerStack(s) && !isToolBearingShulker(s);
    }

    /**
     * "Risky" foods we throw out rather than keep for AutoEat: ones that apply a harmful effect or
     * teleport the bot out of position. Stews are caught separately by the non-stackable check in
     * {@link #isRiskyFood} (and listed here too for clarity).
     */
    private static final java.util.Set<Item> RISKY_FOODS = java.util.Set.of(
        Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
        Items.CHICKEN, Items.CHORUS_FRUIT,
        Items.SUSPICIOUS_STEW, Items.MUSHROOM_STEW, Items.RABBIT_STEW, Items.BEETROOT_SOUP
    );

    /**
     * A food we'd rather drop than keep: an explicitly-listed harmful/teleport food (rotten flesh,
     * spider eye, poisonous/raw foods, pufferfish, chorus fruit), or ANY non-stackable food — which
     * in vanilla is exactly the stews (mushroom/rabbit/beetroot/suspicious), so modded or future
     * specialty foods are covered too. Normal stackable foods (bread, cooked meat, carrots, golden
     * apples, …) are not risky and are kept for AutoEat.
     */
    private boolean isRiskyFood(ItemStack s) {
        if (RISKY_FOODS.contains(s.getItem())) return true;
        return s.contains(DataComponentTypes.FOOD) && s.getMaxCount() == 1; // stews / specialty foods
    }

    /**
     * Drop the first junk slot found (one per call). Returns true if something was dropped.
     */
    private boolean dropOneJunk() {
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            if (isTargetStack(s)) continue;
            if (keepInventoryItems.get().contains(s.getItem())) continue;
            if (isShulker(s)) continue;
            if (s.getItem() == Items.ENDER_CHEST) continue;
            if (s.contains(DataComponentTypes.TOOL)) continue;
            // Keep good food for AutoEat, but let risky foods (rotten flesh, stews, poison/teleport
            // foods) fall through to be dropped when 'drop-bad-food' is on.
            if (s.contains(DataComponentTypes.FOOD) && !(dropBadFood.get() && isRiskyFood(s))) continue;
            InvUtils.drop().slot(i);
            return true;
        }
        return false;
    }

    // ---------------- Tool restock ----------------

    /**
     * Top up every tool type we keep stocked from the OPEN ender chest: the main restock-tool, plus a
     * shovel when 'also-restock-shovel' is on. Best-effort — a missing secondary tool only logs, it never
     * pauses the run (the dedicated tool-shulker cycle still guards the PRIMARY tool).
     */
    private void restockTool(ScreenHandler h) {
        boolean primary = true;
        for (ToolType t : restockToolTypes()) {
            restockOneTool(h, t, primary);
            primary = false;
        }
    }

    /**
     * Pull one fresh tool of type {@code t} from the open chest if we don't already hold a fresh one.
     */
    private void restockOneTool(ScreenHandler h, ToolType t, boolean primary) {
        if (hasFreshTool(t)) return;             // a fresh spare is already in reach — AutoTool will swap to it
        dbg("restock check: no fresh %s on hand -> looking in ender chest", t);
        // Match the tool type specifically (by item type, so custom names/enchants don't matter) — a
        // named axe/sword in the chest can't be grabbed by mistake.
        int slot = findContainerSlot(h, s ->
            isToolOfType(s, t)
                && (s.getMaxDamage() - s.getDamage()) >= restockDurability.get()
                && (!reserveSilk.get() || !Utils.hasEnchantments(s, Enchantments.SILK_TOUCH)));
        if (slot >= 0) {
            int free = freeHotbarSlot();
            if (free != -1) swapToHotbar(h, slot, free); // into the hotbar so AutoTool/mining can use it
            else quickMove(h, slot);                     // fallback
            dbg("restocked %s into hotbar slot %d", t, free);
            info("Restocked %s.", t.name().toLowerCase());
        } else if (primary) {
            dbg("no fresh %s found in ender chest", t);
            warning("No fresh %s in ender chest.", t.name().toLowerCase());
        } else {
            dbg("no fresh %s (secondary) in ender chest — skipping, mining continues", t);
        }
    }

    /**
     * Tool types kept stocked from the ender chest: the main restock-tool, plus a shovel if 'also-restock-shovel' is on (deduped).
     */
    private List<ToolType> restockToolTypes() {
        ToolType primary = toolType.get() == ToolType.Auto ? autoToolType() : toolType.get();
        List<ToolType> out = new java.util.ArrayList<>(2);
        out.add(primary);
        if (alsoRestockShovel.get() && primary != ToolType.Shovel) out.add(ToolType.Shovel);
        return out;
    }

    /**
     * True if the inventory already holds a fresh (durable, type-matched) tool of this type.
     */
    private boolean hasFreshTool(ToolType t) {
        for (int i = 0; i < 36; i++) if (isFreshTool(mc.player.getInventory().getStack(i), t)) return true;
        return false;
    }

    /**
     * True if the stack is a tool of the given type, regardless of custom name or enchants.
     */
    private boolean isToolOfType(ItemStack s, ToolType t) {
        Item i = s.getItem();
        return switch (t) {
            case Pickaxe -> i == Items.WOODEN_PICKAXE || i == Items.STONE_PICKAXE || i == Items.IRON_PICKAXE
                || i == Items.GOLDEN_PICKAXE || i == Items.DIAMOND_PICKAXE || i == Items.NETHERITE_PICKAXE;
            case Shovel -> i == Items.WOODEN_SHOVEL || i == Items.STONE_SHOVEL || i == Items.IRON_SHOVEL
                || i == Items.GOLDEN_SHOVEL || i == Items.DIAMOND_SHOVEL || i == Items.NETHERITE_SHOVEL;
            case Axe -> i == Items.WOODEN_AXE || i == Items.STONE_AXE || i == Items.IRON_AXE
                || i == Items.GOLDEN_AXE || i == Items.DIAMOND_AXE || i == Items.NETHERITE_AXE;
            case Hoe -> i == Items.WOODEN_HOE || i == Items.STONE_HOE || i == Items.IRON_HOE
                || i == Items.GOLDEN_HOE || i == Items.DIAMOND_HOE || i == Items.NETHERITE_HOE;
            case Auto -> isToolOfType(s, autoToolType());
        };
    }

    /**
     * Pick the tool type matching the mined blocks' mineable tag (sand→shovel, deepslate→pickaxe …).
     */
    private ToolType autoToolType() {
        for (Block b : mineBlocks.get()) {
            var st = b.getDefaultState();
            if (st.isIn(BlockTags.SHOVEL_MINEABLE)) return ToolType.Shovel;
            if (st.isIn(BlockTags.AXE_MINEABLE)) return ToolType.Axe;
            if (st.isIn(BlockTags.HOE_MINEABLE)) return ToolType.Hoe;
            if (st.isIn(BlockTags.PICKAXE_MINEABLE)) return ToolType.Pickaxe;
        }
        return ToolType.Pickaxe;
    }

    // ---------------- Tool-shulker restock cycle ----------------
    // A self-contained mini state-machine (entered as State.RESTOCK after CLEAR_AREA opens a pocket)
    // that cracks a TOOL-SHULKER stored in the ender chest to refill a fresh tool, then puts the
    // shulker back. Every world/container action is on its own tick + action-delay, exactly like the
    // storage cycle, so it never bursts packets. Because we match the tool-shulker by its CONTENTS
    // (a shulker holding a fresh tool of the chosen type), loot shulkers are never touched and the
    // tool-shulker needs no special colour or name. Aborts (no tool-shulker found, etc.) still recover
    // the placed ender chest before pausing, so nothing is left in the world.

    /**
     * Advance to a restock sub-phase, resetting the shared step/attempts counters.
     */
    private void setRestockPhase(RestockPhase p) {
        if (p != restockPhase) dbg("restock %s -> %s", restockPhase, p);
        restockPhase = p;
        step = 0;
        attempts = 0;
    }

    private void tickRestock() {
        final ToolType t = toolType.get() == ToolType.Auto ? autoToolType() : toolType.get();
        switch (restockPhase) {
            case PLACE_ECHEST -> {
                switch (tickPlace(s -> s.getItem() == Items.ENDER_CHEST)) {
                    case DONE -> {
                        placedEchest = pendingPlace;
                        dbg("restock: placed echest at %s", placedEchest.toShortString());
                        setRestockPhase(RestockPhase.OPEN_ECHEST);
                        timer = nextDelay();
                    }
                    case FAILED -> pause("Restock: couldn't place ender chest.");
                    case BUSY -> {
                    }
                }
            }
            case OPEN_ECHEST -> {
                if (step == 0) {
                    openBlock(placedEchest);
                    step = 1;
                    timer = nextDelay();
                } else if (isContainerOpen()) {
                    setRestockPhase(RestockPhase.TAKE_SHULKER);
                    timer = nextDelay();
                } else if (++attempts >= 20) pause("Restock: ender chest didn't open.");
                else timer = nextDelay();
            }
            case TAKE_SHULKER -> {
                if (!isContainerOpen()) {
                    pause("Restock: ender chest closed unexpectedly.");
                    return;
                }
                ScreenHandler h = mc.player.currentScreenHandler;
                int slot = findContainerSlot(h, this::isToolShulker);
                if (slot < 0) { // no tool-shulker in the chest — recover the chest, then pause
                    closeScreen();
                    restockAbort = "Restock: no tool-shulker (holding a fresh " + t.name().toLowerCase() + ") in the ender chest.";
                    setRestockPhase(RestockPhase.BREAK_ECHEST);
                    timer = nextDelay();
                    return;
                }
                int free = freeHotbarSlot();
                if (free != -1) swapToHotbar(h, slot, free);
                else quickMove(h, slot);
                dbg("restock: pulled tool-shulker to hotbar slot %d", free);
                setRestockPhase(RestockPhase.CLOSE_ECHEST);
                timer = nextDelay();
            }
            case CLOSE_ECHEST -> {
                closeScreen();
                setRestockPhase(RestockPhase.PLACE_SHULKER);
                timer = nextDelay();
            }
            case PLACE_SHULKER -> {
                switch (tickPlace(this::isToolShulker)) {
                    case DONE -> {
                        placedShulker = pendingPlace;
                        restockShulkersBefore = countShulkers(this::isToolBearingShulker); // placed one is out of inv now
                        dbg("restock: placed tool-shulker at %s (spare tool-shulkers in inv: %d)", placedShulker.toShortString(), restockShulkersBefore);
                        setRestockPhase(RestockPhase.OPEN_SHULKER);
                        timer = nextDelay();
                    }
                    case FAILED -> pause("Restock: couldn't place the tool-shulker — " + placeFail + ".");
                    case BUSY -> {
                    }
                }
            }
            case OPEN_SHULKER -> {
                if (step == 0) {
                    openBlock(placedShulker);
                    step = 1;
                    timer = nextDelay();
                } else if (isContainerOpen()) {
                    setRestockPhase(RestockPhase.TAKE_TOOL);
                    timer = nextDelay();
                } else if (++attempts >= 20) pause("Restock: tool-shulker didn't open.");
                else timer = nextDelay();
            }
            case TAKE_TOOL -> {
                if (!isContainerOpen()) {
                    pause("Restock: tool-shulker closed unexpectedly.");
                    return;
                }
                ScreenHandler h = mc.player.currentScreenHandler;
                int slot = findContainerSlot(h, s -> isFreshTool(s, t));
                restockTookFresh = slot >= 0;
                if (slot >= 0) {
                    int free = freeHotbarSlot();
                    if (free != -1) swapToHotbar(h, slot, free);
                    else quickMove(h, slot);
                    dbg("restock: took fresh %s into hotbar slot %d", t, free);
                    info("Restocked %s from shulker.", t.name().toLowerCase());
                } else {
                    dbg("restock: no fresh %s left inside the tool-shulker", t); // keep the worn one; still recover/return the shulker
                }
                setRestockPhase(RestockPhase.STOW_TOOL);
                timer = nextDelay();
            }
            case STOW_TOOL -> { // put the worn (not broken) tool(s) back into the shulker — only if we got a fresh one
                if (!isContainerOpen()) {
                    pause("Restock: tool-shulker closed unexpectedly.");
                    return;
                }
                if (!restockTookFresh) {
                    setRestockPhase(RestockPhase.CLOSE_SHULKER);
                    timer = nextDelay();
                    return;
                }
                ScreenHandler h = mc.player.currentScreenHandler;
                int worn = findPlayerSlotInContainer(h, s -> isSpentTool(s, t));
                if (worn >= 0 && containerHasRoomFor(h, h.slots.get(worn).getStack())) {
                    quickMove(h, worn);                 // one worn tool into the shulker, then loop
                    dbg("restock: stowed a spent %s back into the tool-shulker", t);
                    timer = fillMoveDelay();
                    return;
                }
                setRestockPhase(RestockPhase.CLOSE_SHULKER); // no more worn tools (or shulker full)
                timer = nextDelay();
            }
            case CLOSE_SHULKER -> {
                closeScreen();
                setRestockPhase(RestockPhase.BREAK_SHULKER);
                timer = nextDelay();
            }
            case BREAK_SHULKER -> {
                if (placedShulker != null && mc.world.getBlockState(placedShulker).getBlock() instanceof ShulkerBoxBlock) {
                    if (!pickupSnapshotTaken) {
                        beginPickup(this::isToolBearingShulker);
                        pickupSnapshotTaken = true;
                    } // snapshot BEFORE it pops
                    BlockUtils.breakBlock(placedShulker, true); // continuous until it pops (drops with its remaining + stowed tools)
                    return; // breaking must run every tick — no action-delay here
                }
                pickupSnapshotTaken = false;                  // chase the drop before reopening
                setRestockPhase(RestockPhase.PICKUP_SHULKER);
                timer = nextDelay();
            }
            case PICKUP_SHULKER -> { // make sure the broken tool-shulker is back in the inventory before reopening
                switch (tickPickupDrop(placedShulker, this::isToolBearingShulker)) {
                    case BUSY -> {
                    }
                    case GAVE_UP -> {
                        dbg("restock: gave up waiting for the tool-shulker drop — proceeding");
                        placedShulker = null;
                        setRestockPhase(RestockPhase.REOPEN_ECHEST);
                    }
                    case DONE -> {
                        placedShulker = null;
                        setRestockPhase(RestockPhase.REOPEN_ECHEST);
                    }
                }
                timer = nextDelay();
            }
            case REOPEN_ECHEST -> {
                if (step == 0) {
                    openBlock(placedEchest);
                    step = 1;
                    timer = nextDelay();
                } else if (isContainerOpen()) {
                    setRestockPhase(RestockPhase.RETURN_SHULKER);
                    timer = nextDelay();
                } else if (++attempts >= 20) pause("Restock: ender chest didn't reopen.");
                else timer = nextDelay();
            }
            case RETURN_SHULKER -> {
                if (!isContainerOpen()) {
                    pause("Restock: ender chest closed unexpectedly.");
                    return;
                }
                ScreenHandler h = mc.player.currentScreenHandler;
                // step 1: waiting to confirm the last shift-click actually landed the shulker in the chest.
                if (step == 1) {
                    if (countContainerMatches(h, this::isToolBearingShulker) > restockReturnedBefore) {
                        step = 0;
                        attempts = 0;            // confirmed stored — look for another (or finish)
                    } else if (++attempts > 20 * 3) {
                        dbg("restock: store of tool-shulker not confirmed after 3s — retrying"); // re-issue below
                        step = 0;
                        attempts = 0;
                    } else {
                        timer = nextDelay();
                        return;
                    }
                }
                int playerSlot = findPlayerSlotInContainer(h, this::isToolBearingShulker);
                if (playerSlot >= 0 && containerHasRoomFor(h, h.slots.get(playerSlot).getStack())) {
                    restockReturnedBefore = countContainerMatches(h, this::isToolBearingShulker);
                    quickMove(h, playerSlot);
                    step = 1;                              // confirm it lands before recovering the echest
                    timer = fillMoveDelay();
                    return;
                }
                setRestockPhase(RestockPhase.CLOSE_ECHEST2);
                timer = nextDelay();
            }
            case CLOSE_ECHEST2 -> {
                closeScreen();
                setRestockPhase(RestockPhase.BREAK_ECHEST);
                timer = nextDelay();
            }
            case BREAK_ECHEST -> {
                if (!breakAndCollectEchest()) return; // break + chase the ender chest drop before finishing
                setRestockPhase(RestockPhase.DONE);
                timer = nextDelay();
            }
            default -> { // DONE
                restocking = false;
                if (restockAbort != null) {
                    String m = restockAbort;
                    restockAbort = null;
                    pause(m);
                } else {
                    dbg("restock cycle complete — resuming mining");
                    resumeMining();
                }
            }
        }
    }

    // ---------------- Food-shulker restock cycle ----------------
    // A self-contained mini state-machine (entered as State.FOOD_RESTOCK after CLEAR_AREA opens a pocket)
    // that cracks a FOOD-SHULKER stored in the ender chest to refill food, then puts the shulker back. It
    // mirrors the tool-shulker cycle exactly — every world/container action on its own tick + action-delay,
    // and the break/pickup uses the shared settle → chase → confirm tracker — so it never bursts packets and
    // a lag spike can't lose the shulker. The food-shulker is matched by its CONTENTS (a shulker holding
    // edible, non-risky food), so loot shulkers are never touched and it needs no special colour/name. It is
    // best-effort: a missing food-shulker (or one out of food) warns + latches rather than pausing, so a low
    // food supply never stalls an AFK run (running low on food, unlike a broken tool, isn't fatal to mining).

    /**
     * Advance to a food sub-phase, resetting the shared step/attempts counters.
     */
    private void setFoodPhase(FoodPhase p) {
        if (p != foodPhase) dbg("food %s -> %s", foodPhase, p);
        foodPhase = p;
        step = 0;
        attempts = 0;
    }

    private void tickFoodRestock() {
        switch (foodPhase) {
            case PLACE_ECHEST -> {
                switch (tickPlace(s -> s.getItem() == Items.ENDER_CHEST)) {
                    case DONE -> {
                        placedEchest = pendingPlace;
                        dbg("food: placed echest at %s", placedEchest.toShortString());
                        setFoodPhase(FoodPhase.OPEN_ECHEST);
                        timer = nextDelay();
                    }
                    case FAILED -> pause("Food restock: couldn't place ender chest.");
                    case BUSY -> {
                    }
                }
            }
            case OPEN_ECHEST -> {
                if (step == 0) {
                    openBlock(placedEchest);
                    step = 1;
                    timer = nextDelay();
                } else if (isContainerOpen()) {
                    setFoodPhase(FoodPhase.TAKE_SHULKER);
                    timer = nextDelay();
                } else if (++attempts >= 20) pause("Food restock: ender chest didn't open.");
                else timer = nextDelay();
            }
            case TAKE_SHULKER -> {
                if (!isContainerOpen()) {
                    pause("Food restock: ender chest closed unexpectedly.");
                    return;
                }
                ScreenHandler h = mc.player.currentScreenHandler;
                int slot = findContainerSlot(h, this::isFoodShulker);
                if (slot < 0) { // no food-shulker in the chest — recover the chest, then warn + latch (best-effort)
                    closeScreen();
                    foodRestockAbort = "Food restock: no food-shulker (holding edible food) in the ender chest — mining on without a refill.";
                    setFoodPhase(FoodPhase.BREAK_ECHEST);
                    timer = nextDelay();
                    return;
                }
                int free = freeHotbarSlot();
                if (free != -1) swapToHotbar(h, slot, free);
                else quickMove(h, slot);
                dbg("food: pulled food-shulker to hotbar slot %d", free);
                setFoodPhase(FoodPhase.CLOSE_ECHEST);
                timer = nextDelay();
            }
            case CLOSE_ECHEST -> {
                closeScreen();
                setFoodPhase(FoodPhase.PLACE_SHULKER);
                timer = nextDelay();
            }
            case PLACE_SHULKER -> {
                switch (tickPlace(this::isFoodShulker)) {
                    case DONE -> {
                        placedShulker = pendingPlace;
                        dbg("food: placed food-shulker at %s", placedShulker.toShortString());
                        setFoodPhase(FoodPhase.OPEN_SHULKER);
                        timer = nextDelay();
                    }
                    case FAILED -> pause("Food restock: couldn't place the food-shulker — " + placeFail + ".");
                    case BUSY -> {
                    }
                }
            }
            case OPEN_SHULKER -> {
                if (step == 0) {
                    openBlock(placedShulker);
                    step = 1;
                    timer = nextDelay();
                } else if (isContainerOpen()) {
                    setFoodPhase(FoodPhase.TAKE_FOOD);
                    timer = nextDelay();
                } else if (++attempts >= 20) pause("Food restock: food-shulker didn't open.");
                else timer = nextDelay();
            }
            case TAKE_FOOD -> { // pull good food by preference, one stack per tick, up to the restock count
                if (!isContainerOpen()) {
                    pause("Food restock: food-shulker closed unexpectedly.");
                    return;
                }
                ScreenHandler h = mc.player.currentScreenHandler;
                int target = Math.max(foodRestockCount.get(), minFoodOnHand.get());
                if (countGoodFood() >= target) {
                    setFoodPhase(FoodPhase.CLOSE_SHULKER);
                    timer = nextDelay();
                    return;
                }
                int slot = findBestFoodContainerSlot(h);
                if (slot < 0) {                      // shulker has no (more) good food
                    foodShulkerEmptied = true;       // it's now empty of food — keep it recognisable for pickup/return
                    setFoodPhase(FoodPhase.CLOSE_SHULKER);
                    timer = nextDelay();
                    return;
                }
                int free = freeHotbarSlot();
                if (countGoodFood() == 0 && free != -1)
                    swapToHotbar(h, slot, free); // first stack into the hotbar (for AutoEat)
                else quickMove(h, slot);                                             // the rest into the main inventory
                foodTookAny = true;
                timer = fillMoveDelay();
            }
            case CLOSE_SHULKER -> {
                closeScreen();
                setFoodPhase(FoodPhase.BREAK_SHULKER);
                timer = nextDelay();
            }
            case BREAK_SHULKER -> {
                if (placedShulker != null && mc.world.getBlockState(placedShulker).getBlock() instanceof ShulkerBoxBlock) {
                    if (!pickupSnapshotTaken) {
                        beginPickup(this::isReturnableFoodShulker);
                        pickupSnapshotTaken = true;
                    } // snapshot BEFORE it pops
                    BlockUtils.breakBlock(placedShulker, true); // continuous until it pops (drops with its remaining food)
                    return; // breaking must run every tick — no action-delay here
                }
                pickupSnapshotTaken = false;                  // chase the drop before reopening
                setFoodPhase(FoodPhase.PICKUP_SHULKER);
                timer = nextDelay();
            }
            case PICKUP_SHULKER -> { // make sure the broken food-shulker is back in the inventory before reopening
                switch (tickPickupDrop(placedShulker, this::isReturnableFoodShulker)) {
                    case BUSY -> {
                    }
                    case GAVE_UP -> {
                        dbg("food: gave up waiting for the food-shulker drop — proceeding");
                        placedShulker = null;
                        setFoodPhase(FoodPhase.REOPEN_ECHEST);
                    }
                    case DONE -> {
                        placedShulker = null;
                        setFoodPhase(FoodPhase.REOPEN_ECHEST);
                    }
                }
                timer = nextDelay();
            }
            case REOPEN_ECHEST -> {
                if (step == 0) {
                    openBlock(placedEchest);
                    foodReturnedBefore = -1;
                    step = 1;
                    timer = nextDelay();
                } else if (isContainerOpen()) {
                    setFoodPhase(FoodPhase.RETURN_SHULKER);
                    timer = nextDelay();
                } else if (++attempts >= 20) pause("Food restock: ender chest didn't reopen.");
                else timer = nextDelay();
            }
            case RETURN_SHULKER -> {
                if (!isContainerOpen()) {
                    pause("Food restock: 末影箱意外关闭。");
                    return;
                }
                ScreenHandler h = mc.player.currentScreenHandler;
                // step 1: confirm the last shift-click actually landed the shulker in the chest before the next.
                if (step == 1) {
                    if (countContainerMatches(h, this::isReturnableFoodShulker) > foodReturnedBefore) {
                        step = 0;
                        attempts = 0;            // confirmed stored — look for another (or finish)
                    } else if (++attempts > 20 * 3) {
                        dbg("food: store of food-shulker not confirmed after 3s — retrying");
                        step = 0;
                        attempts = 0;
                    } else {
                        timer = nextDelay();
                        return;
                    }
                }
                int playerSlot = findPlayerSlotInContainer(h, this::isReturnableFoodShulker);
                if (playerSlot >= 0 && containerHasRoomFor(h, h.slots.get(playerSlot).getStack())) {
                    foodReturnedBefore = countContainerMatches(h, this::isReturnableFoodShulker);
                    quickMove(h, playerSlot);
                    step = 1;                              // confirm it lands before recovering the echest
                    timer = fillMoveDelay();
                    return;
                }
                setFoodPhase(FoodPhase.CLOSE_ECHEST2);
                timer = nextDelay();
            }
            case CLOSE_ECHEST2 -> {
                closeScreen();
                setFoodPhase(FoodPhase.BREAK_ECHEST);
                timer = nextDelay();
            }
            case BREAK_ECHEST -> {
                if (!breakAndCollectEchest()) return; // break + chase the ender chest drop before finishing
                setFoodPhase(FoodPhase.DONE);
                timer = nextDelay();
            }
            default -> { // DONE
                foodRestocking = false;
                if (!foodTookAny) { // found no food-shulker, or it held no edible food — warn once and latch
                    foodRestockExhausted = true;
                    warning(foodRestockAbort != null ? foodRestockAbort
                        : "补给食物：那个装食物的潜影盒里已无任何可食用之物——只能在不补充补给的情况下继续挖掘。");
                    foodRestockAbort = null;
                } else {
                    dbg("food restock complete (%d good-food item(s) on hand) — resuming mining", countGoodFood());
                    info("已补充食物。");
                }
                resumeMining();
            }
        }
    }

    // ---- Food detection + preference ----

    /**
     * High-saturation processed/cooked staples — preference tier 4 (above natural/uncooked food).
     */
    private static final java.util.Set<Item> COOKED_FOODS = java.util.Set.of(
        Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON,
        Items.COOKED_RABBIT, Items.COOKED_COD, Items.COOKED_SALMON,
        Items.BREAD, Items.BAKED_POTATO, Items.PUMPKIN_PIE, Items.DRIED_KELP
    );

    /**
     * An edible food we're happy to keep/eat: has the FOOD component and isn't a risky (harmful/teleport/stew) food.
     */
    private boolean isGoodFood(ItemStack s) {
        return !s.isEmpty() && s.contains(DataComponentTypes.FOOD) && !isRiskyFood(s);
    }

    /**
     * Total good-food ITEM count (summed stack sizes) in the inventory — the food-restock trigger/target metric.
     */
    private int countGoodFood() {
        return countItemsMatching(this::isGoodFood);
    }

    /**
     * Preference rank for restocking food (lower = grabbed first): golden carrot (1), enchanted golden
     * apple (2), golden apple (3), cooked meats / bread / other processed staples (4), then any other
     * edible food — natural/uncooked like apples and carrots (5).
     */
    private int foodPriority(Item i) {
        if (i == Items.GOLDEN_CARROT) return 1;
        if (i == Items.ENCHANTED_GOLDEN_APPLE) return 2;
        if (i == Items.GOLDEN_APPLE) return 3;
        if (COOKED_FOODS.contains(i)) return 4;
        return 5;
    }

    /**
     * Container-half slot of the highest-preference good food in the open shulker, or -1 if it holds none.
     */
    private int findBestFoodContainerSlot(ScreenHandler h) {
        int containerSlots = h.slots.size() - 36;
        int best = -1, bestPriority = Integer.MAX_VALUE;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack s = h.slots.get(i).getStack();
            if (!isGoodFood(s)) continue;
            int p = foodPriority(s.getItem());
            if (p < bestPriority) {
                bestPriority = p;
                best = i;
            }
        }
        return best;
    }

    /**
     * A shulker that holds at least one good (edible, non-risky) food — the food-shulker the cycle cracks.
     */
    private boolean isFoodShulker(ItemStack s) {
        if (!isShulker(s)) return false;
        var c = s.get(DataComponentTypes.CONTAINER);
        if (c == null) return false;
        return c.stream().anyMatch(this::isGoodFood);
    }

    /**
     * The shulker to chase/return after the food cycle: the food-shulker (still holding food), or — once
     * we've emptied it of food this cycle — the now-empty shulker. Only matching the empty case while
     * {@code foodShulkerEmptied} is set keeps a spare empty shulker from being mistaken for it on a normal
     * (food still inside) refill; on the rare emptied refill a stray empty simply rides back into the
     * echest buffer too, which is harmless (empties belong in the echest anyway).
     */
    private boolean isReturnableFoodShulker(ItemStack s) {
        return isFoodShulker(s) || (foodShulkerEmptied && isEmptyShulkerStack(s));
    }

    /**
     * True when there is NO fresh tool of the restock type anywhere in the inventory — time to restock.
     */
    private boolean toolNeedsRestock() {
        final ToolType t = toolType.get() == ToolType.Auto ? autoToolType() : toolType.get();
        for (int i = 0; i < 36; i++) {
            if (isFreshTool(mc.player.getInventory().getStack(i), t)) return false;
        }
        return true;
    }

    /**
     * A usable spare: right tool type, durability at/above the threshold, and (optionally) not Silk Touch.
     */
    private boolean isFreshTool(ItemStack s, ToolType t) {
        if (s.isEmpty() || !isToolOfType(s, t)) return false;
        if ((s.getMaxDamage() - s.getDamage()) < restockDurability.get()) return false;
        return !reserveSilk.get() || !Utils.hasEnchantments(s, Enchantments.SILK_TOUCH);
    }

    /**
     * A SPENT tool worth stowing back in the shulker: right type, worn below the restock threshold but NOT
     * broken, and never the reserved Silk Touch tool (which stays in the inventory for ender chests).
     */
    private boolean isSpentTool(ItemStack s, ToolType t) {
        if (s.isEmpty() || !isToolOfType(s, t)) return false;
        if (reserveSilk.get() && Utils.hasEnchantments(s, Enchantments.SILK_TOUCH)) return false;
        int rem = s.getMaxDamage() - s.getDamage();
        return rem > 0 && rem < restockDurability.get();
    }

    /**
     * A shulker that holds at least one FRESH tool of the restock type (the one we pull a tool from).
     */
    private boolean isToolShulker(ItemStack s) {
        return shulkerHoldsTool(s, true);
    }

    /**
     * A shulker that holds any tool of the restock type, fresh or worn (the one we put back).
     */
    private boolean isToolBearingShulker(ItemStack s) {
        return shulkerHoldsTool(s, false);
    }

    private boolean shulkerHoldsTool(ItemStack s, boolean requireFresh) {
        if (!isShulker(s)) return false;
        var c = s.get(DataComponentTypes.CONTAINER);
        if (c == null) return false;
        final ToolType t = toolType.get() == ToolType.Auto ? autoToolType() : toolType.get();
        return c.stream().anyMatch(inner -> requireFresh ? isFreshTool(inner, t) : isToolOfType(inner, t));
    }

    // ---------------- Container interaction ----------------
    // Vanilla ScreenHandler + interactionManager APIs. Slot indexing: the player
    // inventory is always the trailing 36 slots of the open handler.

    private boolean isContainerOpen() {
        return mc.player != null
            && mc.player.currentScreenHandler != mc.player.playerScreenHandler;
    }

    private void openBlock(BlockPos pos) {
        if (pos == null) return;
        Vec3d hit = Vec3d.ofCenter(pos);
        Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
            BlockHitResult bhr = new BlockHitResult(hit, Direction.UP, pos, false);
            BlockUtils.interact(bhr, Hand.MAIN_HAND, true);
        });
    }

    private void closeScreen() {
        if (isContainerOpen()) mc.player.closeHandledScreen();
    }

    /**
     * Find a container-half slot (the chest/shulker, not the player rows) matching p.
     */
    private int findContainerSlot(ScreenHandler h, java.util.function.Predicate<ItemStack> p) {
        int containerSlots = h.slots.size() - 36; // player inv is always the trailing 36 slots
        for (int i = 0; i < containerSlots; i++) {
            Slot slot = h.slots.get(i);
            if (p.test(slot.getStack())) return i;
        }
        return -1;
    }

    /**
     * Count stacks in the container half (the chest/shulker, not the player rows) matching p.
     */
    private int countContainerMatches(ScreenHandler h, java.util.function.Predicate<ItemStack> p) {
        int containerSlots = h.slots.size() - 36;
        int n = 0;
        for (int i = 0; i < containerSlots; i++) if (p.test(h.slots.get(i).getStack())) n++;
        return n;
    }

    /**
     * Count the empty slots in the container half (used to size a refill to the echest's free space).
     */
    private int echestFreeSlots(ScreenHandler h) {
        int containerSlots = h.slots.size() - 36;
        int free = 0;
        for (int i = 0; i < containerSlots; i++) if (h.slots.get(i).getStack().isEmpty()) free++;
        return free;
    }

    /**
     * True if the container half (the chest/shulker, not the player rows) can accept {@code s}:
     * either an empty slot, or — for stackable items — a non-full stack of the same item. Used to
     * make fills/stores destination-aware so a blind shift-click can't spin against a full
     * container, and so a filled shulker (non-stackable) only counts an empty slot as room.
     */
    private boolean containerHasRoomFor(ScreenHandler h, ItemStack s) {
        int containerSlots = h.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack c = h.slots.get(i).getStack();
            if (c.isEmpty()) return true;
            if (s.getMaxCount() > 1 && ItemStack.areItemsAndComponentsEqual(c, s) && c.getCount() < c.getMaxCount())
                return true;
        }
        return false;
    }

    /**
     * Find a player-half slot (within an open container) matching p.
     */
    private int findPlayerSlotInContainer(ScreenHandler h, java.util.function.Predicate<ItemStack> p) {
        int total = h.slots.size();
        for (int i = total - 36; i < total; i++) {
            Slot slot = h.slots.get(i);
            if (p.test(slot.getStack())) return i;
        }
        return -1;
    }

    /**
     * Shift-click a slot (moves the stack to the opposite inventory half).
     */
    private void quickMove(ScreenHandler h, int slotIndex) {
        mc.interactionManager.clickSlot(h.syncId, slotIndex, 0, SlotActionType.QUICK_MOVE, mc.player);
    }

    // ---------------- Placement + safety ----------------

    /**
     * Find an air block beside the bot to place storage in, that the game can actually place against.
     * Checks both feet and head level, preferring a spot sitting on a solid block (rests on the floor),
     * then falling back to any air spot with a solid orthogonal face to place against (a wall/floor side).
     * In the top-down thin-layer quarry the bot is often in a 2-tall slot, so head-level + side-face spots
     * matter. Skips anything in {@code avoid} (spots a place packet already failed on) and below the floor.
     */
    private BlockPos findPlacementSpot(java.util.Set<BlockPos> avoid) {
        BlockPos feet = mc.player.getBlockPos();
        // 1) preferred: air beside the bot (feet then head level) resting on a solid block
        for (int dy = 0; dy <= 1; dy++)
            for (Direction dir : HORIZONTAL) {
                BlockPos t = feet.up(dy).offset(dir);
                if (t.getY() < minYLevel.get() || avoid.contains(t)) continue;
                if (isAirAt(t) && isSolidAt(t.down())) return t;
            }
        // 2) fallback: air beside the bot with ANY solid orthogonal face to place against
        for (int dy = 0; dy <= 1; dy++)
            for (Direction dir : HORIZONTAL) {
                BlockPos t = feet.up(dy).offset(dir);
                if (t.getY() < minYLevel.get() || avoid.contains(t)) continue;
                if (isAirAt(t) && hasAnySolidFace(t, feet)) return t;
            }
        return null;
    }

    private boolean isAirAt(BlockPos p) {
        return mc.world.getBlockState(p).isReplaceable();
    }

    private boolean isSolidAt(BlockPos p) {
        return !mc.world.getBlockState(p).isReplaceable();
    }

    /**
     * True if {@code p} has a solid orthogonal neighbour to place against (excluding the bot's own body).
     */
    private boolean hasAnySolidFace(BlockPos p, BlockPos feet) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.offset(d);
            if (n.equals(feet) || n.equals(feet.up())) continue; // the bot itself isn't a support
            if (isSolidAt(n)) return true;
        }
        return false;
    }

    /**
     * First empty hotbar slot (0-8), or -1 if the hotbar is full.
     */
    private int freeHotbarSlot() {
        for (int i = 0; i <= 8; i++) if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        return -1;
    }

    /**
     * Move the item at a (currently-open) container slot into a hotbar slot — single SWAP packet.
     */
    private void swapToHotbar(ScreenHandler h, int fromSlot, int hotbarSlot) {
        mc.interactionManager.clickSlot(h.syncId, fromSlot, hotbarSlot, SlotActionType.SWAP, mc.player);
    }

    /**
     * Keep the hotbar free of target blocks (BepHax-style hotbar cleaning). Vanilla item pickup
     * fills empty hotbar slots before main-inventory slots, so mined blocks pile into the hotbar
     * and leave no room to stage the shulker/ender chest — the root cause of "a shulker gets
     * pulled but never placed". Shift ONE target stack from the hotbar into the main inventory
     * (one QUICK_MOVE packet) per call. MUST only be called when no container is open (it acts on
     * the player's own screen handler), and only when the main inventory can actually receive the
     * stack, so it never spins with no progress. Returns true if it moved one.
     */
    private boolean sweepHotbarTargets() {
        var inv = mc.player.getInventory();
        for (int i = 0; i <= 8; i++) {
            ItemStack s = inv.getStack(i);
            if (!isTargetStack(s)) continue;
            if (!mainHasRoomFor(s)) continue; // can't relocate now; it'll go into the shulker during fill
            // Hotbar inventory index i is screen slot 36+i in the player's own screen handler;
            // shift-clicking it there moves the stack into the main inventory (slots 9-35).
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 36 + i, 0, SlotActionType.QUICK_MOVE, mc.player);
            return true;
        }
        return false;
    }

    /**
     * The main-inventory index (9-35) of the first stack matching {@code pred}, or -1. In the player's own
     * screen handler this index equals the screen slot, so it's directly usable for a SWAP click.
     */
    private int mainSlotMatching(java.util.function.Predicate<ItemStack> pred) {
        var inv = mc.player.getInventory();
        for (int j = 9; j <= 35; j++) if (pred.test(inv.getStack(j))) return j;
        return -1;
    }

    /**
     * A hotbar slot (0-8) we can SWAP a staging item into without losing or disrupting gear: prefer a target
     * block (bulk haul), else any non-pinned item (a spare shulker / ender chest — these just move to the
     * main inventory, nothing lost). Tools, weapons, armour and food are pinned and never displaced. -1 if
     * the whole hotbar is pinned gear.
     */
    private int hotbarSwapSlot() {
        var inv = mc.player.getInventory();
        for (int i = 0; i <= 8; i++) if (isTargetStack(inv.getStack(i))) return i;     // displace a target first
        for (int i = 0; i <= 8; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && !isHotbarPinned(s)) return i;                            // else a spare shulker/echest
        }
        return -1;
    }

    /**
     * Items kept in the hotbar (never displaced to stage something): food (for AutoEat) and anything with
     * durability (tools / weapons / armour). Targets, shulkers and ender chests are NOT pinned.
     */
    private boolean isHotbarPinned(ItemStack s) {
        return s.contains(DataComponentTypes.FOOD) || s.isDamageable();
    }

    /**
     * True if the main inventory (slots 9-35) has an empty slot or a non-full stack matching {@code s}.
     */
    private boolean mainHasRoomFor(ItemStack s) {
        var inv = mc.player.getInventory();
        for (int i = 9; i <= 35; i++) {
            ItemStack m = inv.getStack(i);
            if (m.isEmpty()) return true;
            if (ItemStack.areItemsAndComponentsEqual(m, s) && m.getCount() < m.getMaxCount()) return true;
        }
        return false;
    }

    /**
     * Hunger pause: while MINING, Baritone's quarry re-selects the pickaxe and forces an attack click
     * every tick, which cancels any bite AutoEat starts — so the bot loops eating/breaking and never
     * refills. When food dips to the threshold we release the quarry (caller pauses) so the eat
     * completes, and hold until we're full again (hysteresis, so we don't flap on every single bite).
     * No-op when there's nothing edible to eat — pausing then would just stall the run.
     */
    private boolean hungerPauseActive() {
        if (!pauseToEat.get()) {
            wantToEat = false;
            return false;
        }
        int food = mc.player.getHungerManager().getFoodLevel();
        if (wantToEat) {
            if (food >= 20) wantToEat = false;                       // fully fed -> resume
        } else if (food <= eatBelowHunger.get() && hasEdibleFood()) {
            wantToEat = true;                                        // dipped to threshold, food on hand -> pause
        }
        return wantToEat;
    }

    /**
     * True if the inventory holds any edible food (so a hunger pause can actually accomplish a refill).
     */
    private boolean hasEdibleFood() {
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.contains(DataComponentTypes.FOOD)) return true;
        }
        return false;
    }

    private boolean isHazard() {
        // lava adjacency
        BlockPos p = mc.player.getBlockPos();
        for (Direction d : Direction.values()) {
            if (mc.world.getBlockState(p.offset(d)).getBlock() == Blocks.LAVA) return true;
        }
        // nearby non-self player
        if (hazardPlayerRange.get() > 0) {
            for (var pe : mc.world.getPlayers()) {
                if (pe == mc.player) continue;
                if (pe.distanceTo(mc.player) <= hazardPlayerRange.get()) return true;
            }
        }
        return false;
    }

    @Override
    public String getInfoString() {
        if (state == State.PAUSED && wantToEat) return "paused (eating)";
        if (state == State.COLLECT) return "collecting drops";
        if (state == State.UNSTICK) return "unstick (manual break)";
        if (state == State.SELECT) return "select " + (corner1 == null ? "corner 1" : "corner 2");
        if (state == State.RESTOCK) return "restock " + restockPhase.name().toLowerCase();
        if (state == State.FOOD_RESTOCK) return "food " + foodPhase.name().toLowerCase();
        if (state == State.DEPOSIT) return "deposit " + depositPhase.name().toLowerCase();
        if (areaLimited && state == State.MINING)
            return String.format("mining y%d..%d  chunk %d/%d", curLayerBottomY(), curLayerTopY, areaChunksDone, areaChunksTotal);
        return state.name();
    }
}
