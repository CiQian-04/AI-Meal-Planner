package com.mealplanner.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealplanner.model.IngredientPrice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The ingredient price lookup tool exposed to the model.
 *
 * <p>This is the showcase piece of the project: the LLM does <em>not</em> know any prices.
 * When it needs to cost a dish it has to call {@link #lookupIngredientPrice(String)} and let this
 * class answer. The tool is registered with Spring AI via {@code @Tool}, so the model receives the
 * JSON schema of these methods and decides on its own which ingredients to price and when.
 */
@Component
public class IngredientPriceTools {

	/**
	 * Price database keyed by normalized ingredient name.
	 *
	 * <p>Values are representative Chinese supermarket prices in CNY, deliberately small and
	 * reviewable so the demo output stays believable for a ~100 CNY budget.
	 */
	private static final Map<String, IngredientPrice> PRICE_DB = new LinkedHashMap<>();

	static {
		// 蔬菜
		put("上海青", "500g", 4.00, "蔬菜");
		put("丝瓜", "500g", 5.00, "蔬菜");
		put("冬瓜", "500g", 2.60, "蔬菜");
		put("包菜", "1kg", 5.00, "蔬菜");
		put("南瓜", "500g", 3.50, "蔬菜");
		put("圆白菜", "500g", 2.80, "蔬菜");
		put("土豆", "500g", 2.80, "蔬菜");
		put("大白菜", "1kg", 4.00, "蔬菜");
		put("娃娃菜", "400g", 5.80, "蔬菜");
		put("山药", "500g", 7.00, "蔬菜");
		put("彩椒", "500g", 8.00, "蔬菜");
		put("油菜", "400g", 4.00, "蔬菜");
		put("油麦菜", "400g", 4.20, "蔬菜");
		put("洋葱", "500g", 2.20, "蔬菜");
		put("玉米", "2根", 6.00, "蔬菜");
		put("生菜", "300g", 3.60, "蔬菜");
		put("番茄", "500g", 4.50, "蔬菜");
		put("白菜", "500g", 2.90, "蔬菜");
		put("秋葵", "300g", 8.50, "蔬菜");
		put("空心菜", "400g", 4.50, "蔬菜");
		put("竹笋", "500g", 7.50, "蔬菜");
		put("胡萝卜", "500g", 2.50, "蔬菜");
		put("芋头", "500g", 5.50, "蔬菜");
		put("芦笋", "200g", 12.00, "蔬菜");
		put("芹菜", "500g", 4.20, "蔬菜");
		put("苦瓜", "500g", 4.80, "蔬菜");
		put("茄子", "500g", 3.80, "蔬菜");
		put("茼蒿", "400g", 4.50, "蔬菜");
		put("莲藕", "500g", 6.50, "蔬菜");
		put("莴笋", "500g", 3.40, "蔬菜");
		put("菜花", "500g", 4.80, "蔬菜");
		put("菠菜", "500g", 5.50, "蔬菜");
		put("西兰花", "500g", 6.80, "蔬菜");
		put("西红柿", "500g", 4.50, "蔬菜");
		put("西葫芦", "500g", 4.00, "蔬菜");
		put("豆芽", "500g", 2.50, "蔬菜");
		put("豆角", "500g", 5.20, "蔬菜");
		put("青椒", "500g", 4.20, "蔬菜");
		put("韭菜", "300g", 4.50, "蔬菜");
		put("黄瓜", "500g", 3.20, "蔬菜");
		put("圣女果", "250g", 6.00, "蔬菜");
		put("柠檬", "1个", 3.00, "蔬菜");

		// 菌菇
		put("口蘑", "250g", 8.00, "菌菇");
		put("平菇", "500g", 5.50, "菌菇");
		put("木耳", "100g", 6.00, "菌菇");
		put("木耳干", "50g", 10.00, "菌菇");
		put("杏鲍菇", "300g", 7.00, "菌菇");
		put("白玉菇", "150g", 6.00, "菌菇");
		put("蟹味菇", "150g", 6.00, "菌菇");
		put("金针菇", "300g", 4.50, "菌菇");
		put("香菇", "500g", 7.90, "菌菇");

		// 肉禽
		put("五花肉", "500g", 22.00, "肉禽");
		put("午餐肉", "340g", 15.00, "肉禽");
		put("培根", "200g", 12.00, "肉禽");
		put("排骨", "500g", 35.00, "肉禽");
		put("火腿", "200g", 12.00, "肉禽");
		put("火腿肠", "150g", 4.00, "肉禽");
		put("牛排", "200g", 35.00, "肉禽");
		put("牛肉", "500g", 35.00, "肉禽");
		put("牛肉里脊", "500g", 45.00, "肉禽");
		put("牛肉馅", "500g", 35.00, "肉禽");
		put("牛腩", "500g", 42.00, "肉禽");
		put("猪排骨", "500g", 32.00, "肉禽");
		put("猪肉里脊", "500g", 22.00, "肉禽");
		put("猪肉馅", "500g", 22.00, "肉禽");
		put("猪肝", "250g", 10.00, "肉禽");
		put("猪里脊", "500g", 19.90, "肉禽");
		put("羊肉", "500g", 45.00, "肉禽");
		put("羊肉卷", "500g", 35.00, "肉禽");
		put("鸡爪", "500g", 15.00, "肉禽");
		put("鸡翅", "500g", 18.00, "肉禽");
		put("鸡翅中", "500g", 25.00, "肉禽");
		put("鸡胗", "300g", 12.00, "肉禽");
		put("鸡胸肉", "500g", 9.90, "肉禽");
		put("鸡腿", "500g", 12.50, "肉禽");
		put("鸭肉", "500g", 16.00, "肉禽");
		put("鸭腿", "500g", 18.00, "肉禽");

		// 蛋豆
		put("冻豆腐", "400g", 6.00, "蛋豆");
		put("千张", "200g", 6.00, "蛋豆");
		put("咸蛋", "4枚", 8.00, "蛋豆");
		put("嫩豆腐", "400g", 4.00, "蛋豆");
		put("皮蛋", "4枚", 8.00, "蛋豆");
		put("腐竹", "100g", 8.00, "蛋豆");
		put("豆皮", "150g", 5.00, "蛋豆");
		put("豆腐", "400g", 3.50, "蛋豆");
		put("香干", "200g", 6.00, "蛋豆");
		put("鸡蛋", "10个", 8.50, "蛋豆");
		put("鸭血", "300g", 5.00, "蛋豆");
		put("鹌鹑蛋", "10枚", 15.00, "蛋豆");

		// 水产
		put("三文鱼", "250g", 45.00, "水产");
		put("带鱼", "500g", 22.00, "水产");
		put("干贝", "50g", 25.00, "水产");
		put("海带", "300g", 5.00, "水产");
		put("紫菜", "10g", 8.00, "水产");
		put("草鱼", "500g", 14.00, "水产");
		put("虾", "500g", 30.00, "水产");
		put("虾仁", "250g", 26.00, "水产");
		put("虾米", "50g", 15.00, "水产");
		put("蛤蜊", "500g", 12.00, "水产");
		put("鱿鱼", "300g", 18.00, "水产");
		put("鲈鱼", "500g", 28.00, "水产");
		put("龙利鱼柳", "500g", 20.00, "水产");

		// 粮油
		put("大米", "500g", 2.80, "粮油");
		put("小米", "500g", 6.00, "粮油");
		put("年糕", "400g", 7.00, "粮油");
		put("意大利面", "500g", 8.00, "粮油");
		put("挂面", "500g", 5.20, "粮油");
		put("春饼", "10张", 6.00, "粮油");
		put("燕麦", "500g", 8.00, "粮油");
		put("燕麦片", "500g", 10.00, "粮油");
		put("粉丝", "200g", 4.80, "粮油");
		put("糙米", "500g", 4.50, "粮油");
		put("花卷", "10个", 10.00, "粮油");
		put("面条", "500g", 4.50, "粮油");
		put("面粉", "1kg", 6.00, "粮油");
		put("饺子皮", "500g", 8.50, "粮油");
		put("馒头", "10个", 10.00, "粮油");

		// 调味
		put("大葱", "200g", 3.50, "调味");
		put("大蒜", "100g", 3.50, "调味");
		put("姜", "100g", 3.00, "调味");
		put("孜然粉", "50g", 6.00, "调味");
		put("干辣椒", "50g", 6.00, "调味");
		put("料酒", "500ml", 9.50, "调味");
		put("橄榄油", "500ml", 35.00, "调味");
		put("沙拉酱", "200g", 10.00, "调味");
		put("淀粉", "200g", 3.50, "调味");
		put("生抽", "500ml", 7.50, "调味");
		put("番茄酱", "200g", 5.00, "调味");
		put("白糖", "500g", 5.00, "调味");
		put("盐", "500g", 2.50, "调味");
		put("米醋", "500ml", 6.00, "调味");
		put("老抽", "500ml", 8.00, "调味");
		put("胡椒粉", "100g", 8.00, "调味");
		put("芝麻酱", "200g", 12.00, "调味");
		put("花椒", "100g", 12.00, "调味");
		put("花生油", "1L", 25.00, "调味");
		put("菜籽油", "1L", 18.00, "调味");
		put("蚝油", "500g", 13.00, "调味");
		put("蜂蜜", "500g", 25.00, "调味");
		put("豆瓣酱", "500g", 12.00, "调味");
		put("醋", "500ml", 6.50, "调味");
		put("食用油", "500ml", 9.90, "调味");
		put("香油", "250ml", 11.00, "调味");
		put("香醋", "500ml", 8.00, "调味");
		put("黄豆酱", "500g", 10.00, "调味");
		put("黑胡椒粉", "50g", 8.00, "调味");
		put("可乐", "330ml", 3.00, "调味");
		put("蒸鱼豉油", "500ml", 10.00, "调味");

		// 乳品
		put("纯牛奶", "1L", 10.00, "乳品");
		put("芝士片", "200g", 18.00, "乳品");
		put("酸奶", "500g", 10.00, "乳品");
		put("黄油", "200g", 20.00, "乳品");
	}

	private static void put(String name, String unit, double unitPrice, String category) {
		PRICE_DB.put(normalize(name), new IngredientPrice(true, name, unit, unitPrice, category, List.of()));
	}

	private final ObjectMapper objectMapper;

	public IngredientPriceTools(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * Look up the current price of a single ingredient.
	 *
	 * <p>Called by the model through Spring AI function calling. The return value is serialized
	 * to JSON and fed back into the conversation, so the model can reason about cost.
	 *
	 * @param name ingredient name in Chinese, e.g. "鸡胸肉"
	 * @return JSON payload with the unit price, unit and shopping category
	 */
	@Tool(name = "lookupIngredientPrice",
			description = """
					查询单个食材的当前单价（单位：人民币元）。当需要为菜品估算成本、判断是否符合预算时调用。
					参数 name 使用中文食材名。返回包含 unit（计价单位）、unitPrice（单价）、category（购物分类）。
					若找不到完全匹配的食材，会返回 found=false 以及若干相近食材的建议价格。""")
	public String lookupIngredientPrice(
			@ToolParam(description = "食材名称，使用中文，例如「鸡胸肉」「番茄」「鸡蛋」", required = true) String name) {
		IngredientPrice price = PRICE_DB.get(normalize(name));

		if (price == null) {
			List<IngredientPrice> alternatives = findSimilar(name);
			IngredientPrice miss = IngredientPrice.notFound(name, alternatives);
			return toJson(miss);
		}
		return toJson(price);
	}

	/**
	 * Look up the prices of several ingredients in one call.
	 *
	 * <p>Batching keeps the number of round trips low when the model prices a whole dish.
	 *
	 * @param names ingredient names in Chinese
	 * @return JSON payload with one entry per ingredient
	 */
	@Tool(name = "lookupIngredientPrices",
			description = """
					批量查询多个食材的单价（单位：人民币元），用于一次性为一道菜或一天的菜单估算成本。
					参数 names 是中文食材名数组。返回数组，每项含 name、unit、unitPrice、category、found。""")
	public String lookupIngredientPrices(
			@ToolParam(description = "食材名称数组，使用中文，例如 [\"鸡胸肉\", \"番茄\"]", required = true) List<String> names) {
		List<IngredientPrice> results = new ArrayList<>();
		for (String name : names == null ? List.<String>of() : names) {
			IngredientPrice price = PRICE_DB.get(normalize(name));
			results.add(price != null ? price : IngredientPrice.notFound(name, findSimilar(name)));
		}
		return toJson(results);
	}

	/**
	 * Estimate the cost of a whole dish from its ingredient list.
	 *
	 * @param ingredients comma separated "name:quantity:unit" triples, e.g. "鸡胸肉:300:g"
	 * @return JSON payload with the per-ingredient breakdown and the total
	 */
	@Tool(name = "estimateDishCost",
			description = """
					根据食材清单估算一道菜的总成本（单位：人民币元），并给出每种食材的成本占比。
					ingredients 形如 "鸡胸肉:300:g" 的逗号分隔字符串，格式为 名称:数量:单位。
					返回 total（总成本）与 items 明细。""")
	public String estimateDishCost(
			@ToolParam(description = "食材清单，形如 \"鸡胸肉:300:g,番茄:200:g\"，名称:数量:单位", required = true) String ingredients) {
		List<Map<String, Object>> items = new ArrayList<>();
		double total = 0.0;

		if (ingredients != null && !ingredients.isBlank()) {
			for (String part : ingredients.split(",")) {
				String[] parts = part.trim().split(":");
				if (parts.length < 2) {
					continue;
				}
				String name = parts[0].trim();
				double quantity = parseQuantity(parts[1]);
				String unit = parts.length > 2 ? parts[2].trim() : "";

				IngredientPrice price = PRICE_DB.get(normalize(name));
				double cost = price != null ? costFromPrice(price, quantity, unit) : 0.0;
				total += cost;

				Map<String, Object> item = new LinkedHashMap<>();
				item.put("name", name);
				item.put("quantity", quantity);
				item.put("unit", unit);
				item.put("estimatedCost", round(cost));
				item.put("found", price != null);
				items.add(item);
			}
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("total", round(total));
		result.put("currency", "CNY");
		result.put("items", items);
		return toJson(result);
	}

	/**
	 * All ingredient names known to the price database.
	 *
	 * <p>Not a model-facing tool; used by the backend to price the shopping list and to populate
	 * the ingredient picker in the UI.
	 */
	public List<String> knownIngredientNames() {
		return PRICE_DB.values().stream().map(IngredientPrice::name).distinct().sorted().toList();
	}

	/**
	 * Find ingredients whose name is close to the requested one.
	 *
	 * <p>Scores candidates by shared characters so that a miss on "鸡翅" still surfaces "鸡腿" and
	 * "鸡胸肉" instead of leaving the model with nothing.
	 */
	private List<IngredientPrice> findSimilar(String name) {
		if (name == null || name.isBlank()) {
			return List.of();
		}
		String target = normalize(name);

		List<Scored> scored = new ArrayList<>();
		for (IngredientPrice candidate : PRICE_DB.values()) {
			int score = similarity(target, normalize(candidate.name()));
			if (score > 0) {
				scored.add(new Scored(candidate, score));
			}
		}
		scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
		return scored.stream().limit(3).map(Scored::price).toList();
	}

	/** A price entry paired with its similarity score, used only for ranking alternatives. */
	private record Scored(IngredientPrice price, int score) {
	}

	/**
	 * Simple character-overlap score between two ingredient names.
	 *
	 * @return 0 when the names share nothing, otherwise a positive score
	 */
	private int similarity(String a, String b) {
		if (a.isEmpty() || b.isEmpty()) {
			return 0;
		}
		if (a.equals(b)) {
			return 100;
		}
		if (a.contains(b) || b.contains(a)) {
			return 80;
		}

		int shared = 0;
		for (char ch : a.toCharArray()) {
			if (b.indexOf(ch) >= 0) {
				shared++;
			}
		}
		return shared;
	}

	/** Normalize so that "西红柿" and "番茄", or stray whitespace, still match. */
	private static String normalize(String name) {
		if (name == null) {
			return "";
		}
		return name.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("（", "(").replace("）", ")");
	}

	/**
	 * Reads the base quantity out of a price entry such as {@code "500g"}.
	 *
	 * <p>The price database stores the pack size together with the unit, so the number has to be
	 * split off before any unit conversion can happen.
	 *
	 * @return the pack size, or 0 when unparseable
	 */
	public static double parsePriceQuantity(String priceUnit) {
		if (priceUnit == null || priceUnit.isBlank()) {
			return 0.0;
		}
		String trimmed = priceUnit.trim();
		StringBuilder digits = new StringBuilder();
		for (int index = 0; index < trimmed.length(); index++) {
			char ch = trimmed.charAt(index);
			if (Character.isDigit(ch) || ch == '.') {
				digits.append(ch);
			}
			else {
				break;
			}
		}
		return parseQuantity(digits.toString());
	}

	/**
	 * Reads the unit out of a price entry such as {@code "500g"}.
	 */
	public static String parsePriceUnitName(String priceUnit) {
		if (priceUnit == null || priceUnit.isBlank()) {
			return "";
		}
		String trimmed = priceUnit.trim();
		int index = 0;
		while (index < trimmed.length() && (Character.isDigit(trimmed.charAt(index)) || trimmed.charAt(index) == '.')) {
			index++;
		}
		return trimmed.substring(index).trim();
	}

	/**
	 * Convert an amount into a comparable base unit (grams for weight, millilitres for volume).
	 *
	 * <p>Public so the shopping list pricing in {@code IngredientPriceService} uses exactly the
	 * same conversion rules as the tool exposed to the model.
	 */
	public static double toBaseUnits(double quantity, String unit) {
		String u = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
		return switch (u) {
			case "kg", "千克", "公斤" -> quantity * 1000;
			case "g", "克" -> quantity;
			case "l", "升" -> quantity * 1000;
			case "ml", "毫升" -> quantity;
			default -> quantity;
		};
	}

	/**
	 * Cost of buying {@code quantity} of {@code unit}, given a price entry, in CNY.
	 *
	 * <p>Shared by the tool and the shopping list service so both sides always agree.
	 */
	public static double costFromPrice(IngredientPrice price, double quantity, String unit) {
		double base = toBaseUnits(quantity, unit);
		double packSize = toBaseUnits(parsePriceQuantity(price.unit()), parsePriceUnitName(price.unit()));
		if (packSize <= 0) {
			return 0.0;
		}
		return round(base / packSize * price.unitPrice());
	}

	/** Parse a numeric string, returning 0 when it is not a number. */
	private static double parseQuantity(String raw) {
		try {
			return Double.parseDouble(raw.trim());
		}
		catch (NumberFormatException ex) {
			return 0.0;
		}
	}

	/** Round to two decimals, the precision used for every price in the app. */
	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			return "{\"error\":\"failed to serialize price result\"}";
		}
	}
}
