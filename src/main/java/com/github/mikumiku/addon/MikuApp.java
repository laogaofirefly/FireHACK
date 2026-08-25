package com.github.mikumiku.addon;

import com.github.mikumiku.addon.commands.CommandMiku;
import com.github.mikumiku.addon.hud.ForemanScanHud;
import com.github.mikumiku.addon.hud.HudMiku;
import com.github.mikumiku.addon.hud.PotCountHud;
import com.github.mikumiku.addon.modules.*;
import com.github.mikumiku.addon.modules.sorter.ItemSorterModule;
import com.github.mikumiku.addon.util.DebugModule;
import com.github.mikumiku.addon.util.timer.TickTimerManager;
import lombok.extern.slf4j.Slf4j;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.renderer.text.FontFamily;
import meteordevelopment.meteorclient.renderer.text.FontInfo;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;

@Slf4j
public class MikuApp {
    private static boolean authenticated = false;
    private static boolean shown = false;

    public static void onRegisterCategories() {
        Modules.registerCategory(BaseModule.CATEGORY);
        Modules.registerCategory(BaseModule.CATEGORY_MIKU_COMBAT);
        Modules.registerCategory(BaseModule.CATEGORY_MIKU_PRO);
        Modules.registerCategory(BaseModule.CATEGORY_MIKU_BUILD);
    }

    public static void init() {
        log.info("Initializing Meteor Addon Miku");

        TickTimerManager.INSTANCE.getTickTime();
        // Modules
        Modules modules = Modules.get();
//        modules.add(new ModuleExample());
        modules.add(new PlayerAlert());
        modules.add(new AutoTrashModule());
        modules.add(new LiquidFiller());
        modules.add(new AutoMiner());
        modules.add(new AutoUseItems());
        modules.add(new TreeAura());
        modules.add(new SeedMine());
        modules.add(new OnekeyFireWork());
        modules.add(new ShulkerBoxItemFetcher());
        modules.add(new VillagerRoller());
        modules.add(new AutoWalk());
        modules.add(new HandsomeSpin());
        modules.add(new AutoCrystalBlock());
        modules.add(new AutoLog());
        modules.add(new ElytraUnbreak());
        modules.add(new GhostMine());
        modules.add(new AutoWither());
        modules.add(new AnchorAuraPlus());
        modules.add(new AutoEz());
        modules.add(new RoadBuilder());
        modules.add(new SelfTrapPlusPlus());
        modules.add(new AutoSM());
        modules.add(new AutoLoginPlus());
        modules.add(new NetherSearchArea());
        modules.add(new NoFall());
        modules.add(new EntityList());
        modules.add(new AutoTouchFire());
        modules.add(new FarmHelper());
        modules.add(new AutoXP());
        modules.add(new ChestAura());
        modules.add(new FastFall());
//        modules.add(new TridentFly());
        modules.add(new ElytraFlyPlus());
        modules.add(new Hover());
        modules.add(new HighwayBlocker());
        modules.add(new HighwayClearer());
        modules.add(new Criticals());
        modules.add(new MaceCombo());
//        modules.add(new AutoFollowPlayer());
        modules.add(new OneKeyPearl());
        modules.add(new NoJumpDelay());
        modules.add(new Scaffold());
        modules.add(new BedrockFinder());
//        modules.add(new ChestplateFly());
//        Modules.get().add(new ElytraFlyPlusPlus());

        modules.add(new UserGuide());
        modules.add(new AutoSlab());
        modules.add(new FishingRodFace());
        modules.add(new KillAuraMiku());
        modules.add(new PearlMark());
        modules.add(new UniversalSupply());
//        modules.add(new CometTunnel());
//        modules.add(new UniversalSupplySystem());
        modules.add(new ExtendedFirework());
        modules.add(new Phase());
        modules.add(new AutoCrystal());
        modules.add(new AutoSpray());

//        modules.add(new HeadlessModule(CATEGORY_MIKU_PRO, "投影打印机", "内测中"));
        modules.add(new AutoElytraHarvester());
        modules.add(new BaritoneHelperModule());

        // 地图画 Modules
//        Modules.get().add(new CarpetPrinter());
//        Modules.get().add(new FullBlockPrinter());

        Modules.get().add(new Surround());
        Modules.get().add(new SurroundPlus());
        Modules.get().add(new SurroundPlus2());
        Modules.get().add(new AutoDigFeet());
        Modules.get().add(new AutoHoleFill());
        Modules.get().add(new HoleSnap());
        Modules.get().add(new AutoBlockCenter());

        Modules.get().add(new FakeCoordinates());
        Modules.get().add(new ItemSorterModule());

        Modules.get().add(new Velocity());
        Modules.get().add(new AntiKnockback());
        Modules.get().add(new PearlBot());
        Modules.get().add(new DebugModule());
        Modules.get().add(new VillagerTrader());
        Modules.get().add(new ElytraArmorSwitch());
        Modules.get().add(new AutoChorusFruit());
        Modules.get().add(new DeathAutoCommand());
        Modules.get().add(new MassExtractor());
        modules.add(new HeadlessModule(BaseModule.CATEGORY_MIKU_PRO, "药水显示", "在顶部点击Hud-Edit, 然后右键添加， 添加出来后右键元素可以设置"));

//        modules.add(new HeadlessModule(CATEGORY_MIKU_PRO, "赶路助手", "一键"));
//        modules.add(new HeadlessModule(CATEGORY_MIKU_MAP, "鱼竿糊脸", "一键"));
//        modules.add(new HeadlessModule(CATEGORY_MIKU_PRO, "核爆挖掘", "一键"));

//        modules.add(new HeadlessModule(BaseModule.CATEGORY_MIKU_PRO, "全自动KIT", "一键"));
//        modules.add(new HeadlessModule(BaseModule.CATEGORY_MIKU_PRO, "全自动合成", "一键"));
//        modules.add(new HeadlessModule(BaseModule.CATEGORY_MIKU_PRO, "自动袭击", "一键"));
//        modules.add(new HeadlessModule(BaseModule.CATEGORY_MIKU_PRO, "自动喷神龟", "一键"));
//        modules.add(new HeadlessModule(BaseModule.CATEGORY_MIKU_PRO, "自动口服神龟", "一键"));
//        modules.add(new HeadlessModule(BaseModule.CATEGORY_MIKU_PRO, "自动配补给包", "一键"));
//        modules.add(new HeadlessModule(BaseModule.CATEGORY_MIKU_PRO, "自动附魔", "一键"));
//        modules.add(new HeadlessModule(BaseModule.CATEGORY_MIKU_PRO, "末地自动拿鞘翅", "一键"));
//        modules.add(new HeadlessModule(BaseModule.CATEGORY_MIKU_PRO, "自动卡服机", "一键"));

//        modules.add(new HeadlessModule(BaseModule.CATEGORY_MIKU_PRO, "全自动地图画", "地图画"));
//        modules.add(new HeadlessModule(BaseModule.CATEGORY_MIKU_PRO, "地图画基座", "地图画"));


//        Fucker fucker = new Fucker();

//        modules.add(new MikuModule(CATEGORY, "miku", "miku"));
//        MikuModule mikuModule = new MikuModule(CATEGORY, "miku插件", "miku");
        ChatUtils.warning("Miku插件群：1013297171");
        // Commands
        Commands.add(new CommandMiku());

        // HUD
        Hud.get().register(HudMiku.INFO);
        Hud.get().register(PotCountHud.INFO);
        Hud.get().register(ForemanScanHud.INFO);

        try {
            Config config = Config.get();
            config.customFont.set(true);
            // 查找并设置Dengxian字体
            FontFamily dengxianFamily = Fonts.getFamily("Dengxian");

            if (dengxianFamily != null) {
                FontFace dengxianFont = dengxianFamily.get(FontInfo.Type.Regular);
                if (dengxianFont != null) {
                    config.font.set(dengxianFont);
                }
            }
        } catch (Exception e) {

        }

//        if (!autoSM.isActive()) {
//            autoSM.toggle();
//        }


    }

}
