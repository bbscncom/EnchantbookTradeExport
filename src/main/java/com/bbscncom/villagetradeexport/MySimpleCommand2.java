package com.bbscncom.villagetradeexport;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class MySimpleCommand2 {
    private static final Logger LOGGER = LogManager.getLogger();
    // 正则：匹配结尾的空格加罗马数字 (例如 " IV", " III")
    private static final Pattern ROMAN_NUMERALS = Pattern.compile("\\s(?=[MDCLXVI])M*(C[MD]|D?C{0,3})(X[CL]|L?X{0,3})(I[XV]|V?I{0,3})$");

    public MySimpleCommand2(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("analyze_trades2")
                .requires(s -> s.hasPermission(2))
                .executes(MySimpleCommand2::runAnalysis));
    }

    static class TradeStat {
        String cleanLocalName; // 去重用的中文名 (去掉了罗马数字)
        String registryName;   // minecraft:fortune
        int maxLevel = 0;      // 记录出现的最高等级
        int count = 0;         // 出现次数
        MerchantOffer bestOffer; // 记录最高等级对应的那次交易详情
        String description;    // 抓取的 Shift 描述文本

        Map<Integer, Integer> levelCounts = new HashMap<>();
    }

    private static int runAnalysis(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSystemMessage(Component.literal("开始模拟 10w 次交易生成... 结果将输出到 logs/latest.log"));

        var tradesMap = VillagerTrades.TRADES.get(VillagerProfession.LIBRARIAN);
        if (tradesMap == null || !tradesMap.containsKey(1)) return 0;

        VillagerTrades.ItemListing[] listings = tradesMap.get(1);
        Map<String, TradeStat> stats = new HashMap<>();
        RandomSource random = RandomSource.create();

        // --- 1. 循环模拟 100,000 次 ---
        for (int i = 0; i < 100000; i++) {
            for (VillagerTrades.ItemListing listing : listings) {
                MerchantOffer offer = listing.getOffer(null, random);
                if (offer != null && offer.getResult().is(Items.ENCHANTED_BOOK)) {
                    processOffer(offer, stats);
                }
            }
        }

        // --- 2. 输出结果 ---
        outputResults(stats);

        context.getSource().sendSystemMessage(Component.literal("分析完成！"));
        return 1;
    }

    private static void processOffer(MerchantOffer offer, Map<String, TradeStat> stats) {
        ItemStack result = offer.getResult();
        Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(result);

        enchants.forEach((enchant, level) -> {
            // 1. 获取注册名 (modid:name) -> minecraft:fortune
            String registryKey = ForgeRegistries.ENCHANTMENTS.getKey(enchant).toString();

            // 2. 获取本地名称并去重 (时运 III -> 时运)
            String localFullName = enchant.getFullname(level).getString();
            String cleanName = ROMAN_NUMERALS.matcher(localFullName).replaceAll("").trim();

            // 使用 cleanName + registryKey 作为唯一索引，防止不同Mod有同名附魔混淆
            String uniqueKey = registryKey;

            TradeStat stat = stats.computeIfAbsent(uniqueKey, k -> new TradeStat());
            stat.cleanLocalName = cleanName;
            stat.registryName = registryKey;
            stat.count++;

            stat.levelCounts.merge(level, 1, Integer::sum);


            // 3. 尝试获取 Shift 描述
            // Enchantment Descriptions 模组使用的 Key 格式通常是: enchantment.modid.name.desc
            if (stat.description == null) {
                String descKey = enchant.getDescriptionId() + ".desc";
                if (Language.getInstance().has(descKey)) {
                    stat.description = Language.getInstance().getOrDefault(descKey);
                } else {
                    stat.description = "无说明(未在语言文件中找到 .desc)";
                }
            }

            // 4. 更新最高等级记录
            if (level > stat.maxLevel) {
                stat.maxLevel = level;
                stat.bestOffer = offer;
            }

        });
    }

    private static void outputResults(Map<String, TradeStat> stats) {
        // 获取游戏运行的根目录 (即 .minecraft 或 /run)
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path outputPath = gameDir.resolve("trade_analysis_results.txt");

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("==================== 交易统计开始 (模拟 10w 次) ====================\n");
            // 表头
            writer.write(String.format("%-40s | %-6s | %-10s | %-12s | %-4s | %-12s | %-4s | %s\n",
                    "名称(:注册名)", "等级", "权重(次数)", "货币1", "数量", "货币2", "数量", "作用说明"));

            writer.write("----------------------------------------------------------------------------------------------------------------------------------\n");

            stats.values().stream()
                    .sorted(Comparator.comparingInt((TradeStat s) -> -s.count))
                    .forEach(s -> {
                        try {
                            // ... 解析逻辑保持不变 ...
                            String finalName = s.cleanLocalName + ":" + s.registryName;
                            ItemStack costA = s.bestOffer.getBaseCostA();
                            ItemStack costB = s.bestOffer.getCostB();

                            writer.write(String.format("%-40s | %-6d | %-10d | %-12s | %-4d | %-12s | %-4d | %s\n",
                                    finalName, s.maxLevel, s.count,
                                    costA.getHoverName().getString(), costA.getCount(),
                                    costB.isEmpty() ? "无" : costB.getHoverName().getString(),
                                    costB.isEmpty() ? 0 : costB.getCount(),
                                    s.description
                            ));
                            StringBuilder levelLine = new StringBuilder("  等级分布: ");

                            s.levelCounts.entrySet().stream()
                                    .sorted(Map.Entry.comparingByKey())
                                    .forEach(e -> {
                                        int level = e.getKey();
                                        int cnt = e.getValue();
                                        double percent = (cnt * 100.0) / s.count;

                                        levelLine.append(String.format(
                                                "Lv%d: %.2f%%  ", level, percent
                                        ));
                                    });

                            writer.write(levelLine.append("\n").toString());
                        } catch (IOException e) {
                            LOGGER.error("写入行失败", e);
                        }
                    });

            writer.write("==================== 交易统计结束 ====================\n");
            // 在游戏内或控制台提示文件生成的绝对路径，方便寻找
            LOGGER.info("!!! 分析完成 !!! 文件已生成至: {}", outputPath.toAbsolutePath());

        } catch (IOException e) {
            LOGGER.error("无法写入文件: ", e);
        }
    }

    private static void outputResultsOld(Map<String, TradeStat> stats) {
        LOGGER.info("==================== 交易统计开始 ====================");
        // 表头
        LOGGER.info("名称(:modid:name) | 最高等级 | 权重(次数) | 货币1 | 数量 | 货币2 | 数量 | 作用说明");

        stats.values().stream()
                .sorted(Comparator.comparingInt((TradeStat s) -> -s.count)) // 按出现频率降序
                .forEach(s -> {
                    // 格式化名字： 如果中文名和注册名不同，且有非ASCII字符，则拼接
                    String finalName = s.cleanLocalName;
                    if (!finalName.equals(s.registryName)) {
                        finalName = finalName + ":" + s.registryName;
                    }

                    // 解析交易成本 (最高等级时的价格)
                    ItemStack costA = s.bestOffer.getBaseCostA();
                    ItemStack costB = s.bestOffer.getCostB();

                    String currency1 = costA.getHoverName().getString();
                    int count1 = costA.getCount();

                    String currency2 = costB.isEmpty() ? "无" : costB.getHoverName().getString();
                    int count2 = costB.isEmpty() ? 0 : costB.getCount();

                    // 格式化输出行
                    LOGGER.info(String.format("%s | %d | %d | %s | %d | %s | %d | %s",
                            finalName,
                            s.maxLevel,
                            s.count,
                            currency1,
                            count1,
                            currency2,
                            count2,
                            s.description
                    ));
                });
        LOGGER.info("==================== 交易统计结束 ====================");
    }
}