package meteordevelopment.meteorclient.utils.misc;

public final class ChineseLocalization {
    private ChineseLocalization() {}

    public static String moduleTitle(String title) {
        return switch (title) {
            case "Anchor Aura" -> "锚灵光环";
            case "Anti Anchor" -> "防锚灵";
            case "Anti Anvil" -> "防铁砧";
            case "Anti Bed" -> "防床爆";
            case "Arrow Dodge" -> "箭矢躲避";
            case "Attribute Swap" -> "属性切换";
            case "Auto Brewer" -> "自动酿造";
            case "Auto Eat" -> "自动进食";
            case "Auto Farm" -> "自动农场";
            case "Auto Mine" -> "自动挖矿";
            case "Auto Replenish" -> "自动补充";
            case "Auto Trap" -> "自动困人";
            case "Bed Aura" -> "床灵光环";
            case "Burrow" -> "钻入方块";
            case "Cev Breaker" -> "Cev破坏者";
            case "Chat Bot" -> "聊天机器人";
            case "Chest Stealer" -> "箱子窃取";
            case "Click Aura" -> "点击光环";
            case "Criticals" -> "暴击";
            case "Crystal Aura" -> "水晶光环";
            case "Fast Use" -> "快速使用";
            case "Flight" -> "飞行";
            case "Freecam" -> "自由视角";
            case "Health Pot" -> "生命药水";
            case "Hole Filler" -> "坑洞填充";
            case "Jesus" -> "水上行走";
            case "Kill Aura" -> "杀戮光环";
            case "Nuker" -> "核爆挖掘";
            case "Scaffold" -> "自动搭路";
            case "Speed" -> "加速";
            case "Surround" -> "环绕保护";
            case "Trident Bot" -> "三叉戟机器人";
            case "Trigger Bot" -> "触发攻击";
            case "Xray" -> "透视";
            default -> title;
        };
    }
}
