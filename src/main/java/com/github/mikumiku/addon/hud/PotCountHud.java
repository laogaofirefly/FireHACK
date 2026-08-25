package com.github.mikumiku.addon.hud;


import com.github.mikumiku.addon.ok.MikuMikuAddon;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class PotCountHud extends HudElement {
    // 注册该 HUD 元素 (显示在 HUD 编辑器的 Info 类别中)
    public static final HudElementInfo<PotCountHud> INFO = new HudElementInfo<>(MikuMikuAddon.HUD_GROUP, "药水计数", "在屏幕上显示溅射药水数量与抗性剩余时间。", PotCountHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBackground = settings.createGroup("背景");
    private final SettingGroup sgText = settings.createGroup("文本");

    // 常规设置
    private final Setting<Boolean> showSplashPotCount = sgGeneral.add(new BoolSetting.Builder()
        .name("显示溅射药水数量")
        .description("是否显示背包内溅射药水的总数。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showResistanceTime = sgGeneral.add(new BoolSetting.Builder()
        .name("显示抗性时间")
        .description("是否显示高等级抗性提升效果的剩余时间。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> padding = sgGeneral.add(new DoubleSetting.Builder()
        .name("内边距")
        .description("文本与边框之间的间距。")
        .defaultValue(10.0)
        .min(0).sliderMax(20)
        .build()
    );

    // 背景设置
    private final Setting<Boolean> renderBackground = sgBackground.add(new BoolSetting.Builder()
        .name("渲染背景")
        .description("是否渲染HUD背景框。")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> bgColor = sgBackground.add(new ColorSetting.Builder()
        .name("背景颜色")
        .description("背景框的颜色与透明度。")
        .defaultValue(new SettingColor(0, 0, 0, 150))
        .visible(renderBackground::get)
        .build()
    );

    private final Setting<SettingColor> borderColor = sgBackground.add(new ColorSetting.Builder()
        .name("边框颜色")
        .description("背景框边缘的颜色。")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .visible(renderBackground::get)
        .build()
    );

    // 文本设置
    private final Setting<Double> textScale = sgText.add(new DoubleSetting.Builder()
        .name("文本缩放")
        .description("文本的大小倍率。")
        .defaultValue(1.0)
        .min(0.5).max(3.0)
        .build()
    );

    private final Setting<SettingColor> potCountColor = sgText.add(new ColorSetting.Builder()
        .name("药水数量颜色")
        .description("显示药水数量的文本颜色。")
        .defaultValue(new SettingColor(0, 255, 0)) // 绿色
        .visible(showSplashPotCount::get)
        .build()
    );

    private final Setting<SettingColor> timeColor = sgText.add(new ColorSetting.Builder()
        .name("时间颜色")
        .description("显示抗性时间的文本颜色。")
        .defaultValue(new SettingColor(255, 170, 0)) // 橙色
        .visible(showResistanceTime::get)
        .build()
    );

    public PotCountHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        MinecraftClient mc = MinecraftClient.getInstance();
        // 如果不在游戏中，或者没有需要显示的内容，直接返回
        if (mc.player == null || mc.world == null) return;
        if (!showSplashPotCount.get() && !showResistanceTime.get()) return;

        double x = this.x;
        double y = this.y;
        double pad = padding.get();
        double scale = textScale.get();

        // 1. 获取要渲染的数据
        int potCount = countSplashPotions();
        float resTime = getResistanceTimeLeft();

        String potText = "POT: " + potCount;
        String timeText = resTime > 0.0f ? String.format("Time: %.1fs", resTime) : "Time: --";

        // 2. 计算文本的长宽，用于动态调整背景框大小
        double textHeight = renderer.textHeight() * scale;
        double maxWidth = 0;
        int lines = 0;

        if (showSplashPotCount.get()) {
            maxWidth = Math.max(maxWidth, renderer.textWidth(potText) * scale);
            lines++;
        }
        if (showResistanceTime.get()) {
            maxWidth = Math.max(maxWidth, renderer.textWidth(timeText) * scale);
            lines++;
        }

        // 计算包含内边距的总宽高
        double totalWidth = maxWidth + (pad * 2);
        // 行间距预留为 2.0 像素
        double totalHeight = (textHeight * lines) + (lines > 1 ? 2.0 * scale : 0) + (pad * 2);

        // 设置 HUD 边框大小，这样 Meteor 的拖拽框才能完美包裹文本
        setSize(totalWidth, totalHeight);

        // 3. 渲染背景 (使用 Meteor 自带的 Quad 渲染)
        if (renderBackground.get()) {
            // Meteor 的 HudRenderer 目前不原生暴露复杂的模糊着色器和圆角矩形，
            // 最佳实践是画一个主体背景，并附带一条边线 (模拟边框)。
            renderer.quad(x, y, totalWidth, totalHeight, bgColor.get());

            // 绘制顶部、底部、左侧、右侧边框线条 (厚度约2像素)
            double borderThick = 2.0;
            SettingColor bc = borderColor.get();
            renderer.quad(x, y, totalWidth, borderThick, bc); // 顶
            renderer.quad(x, y + totalHeight - borderThick, totalWidth, borderThick, bc); // 底
            renderer.quad(x, y, borderThick, totalHeight, bc); // 左
            renderer.quad(x + totalWidth - borderThick, y, borderThick, totalHeight, bc); // 右
        }

        // 4. 渲染文本 (居中对齐)
        double currentY = y + pad;

        if (showSplashPotCount.get()) {
            double potWidth = renderer.textWidth(potText) * scale;
            double centeredX = x + (totalWidth - potWidth) / 2.0;
            renderer.text(potText, centeredX, currentY, potCountColor.get(), true, scale);
            currentY += textHeight + (2.0 * scale);
        }

        if (showResistanceTime.get()) {
            double timeWidth = renderer.textWidth(timeText) * scale;
            double centeredX = x + (totalWidth - timeWidth) / 2.0;
            renderer.text(timeText, centeredX, currentY, timeColor.get(), true, scale);
        }
    }

    /**
     * 统计玩家背包内所有的溅射药水数量
     */
    private int countSplashPotions() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0;
        int count = 0;
        // 遍历整个玩家背包 (包含主手、副手以及物品栏)
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.SPLASH_POTION) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * 获取玩家抗性提升效果的剩余时间（仅限高等级抗性，例如神龟药水）
     */
    private float getResistanceTimeLeft() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0.0f;
        StatusEffectInstance resistance = mc.player.getStatusEffect(StatusEffects.RESISTANCE);
        // 原版代码判定 Amplifier >= 3 (即抗性等级 IV 或以上)
        if (resistance != null && resistance.getAmplifier() >= 3) {
            return resistance.getDuration() / 20.0f;
        }
        return 0.0f;
    }
}
