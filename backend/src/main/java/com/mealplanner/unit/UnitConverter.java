package com.mealplanner.unit;

import java.util.Locale;

/**
 * Parses the unit strings that travel through the pipeline and converts them into a comparable
 * base amount.
 *
 * <p>The model is not consistent about how it writes units. Depending on the run it may answer
 * with an absolute amount plus a base unit ({@code 300 g}), with the pack size of the price
 * database as the unit ({@code 1.0 × "500g"}), or with a Chinese unit word ({@code 2 根},
 * {@code 3 个}, {@code 15 毫升}). This class turns all of those into
 * {@link Kind#WEIGHT} grams, {@link Kind#VOLUME} millilitres or {@link Kind#COUNT} items, so the
 * pricing code can finally compare an amount with a pack size.
 *
 * <p>It is deliberately dependency free: the price database, the model facing tool and the
 * shopping list all share it, which is what keeps the numbers on screen and the numbers on the
 * receipt in sync.
 */
public final class UnitConverter {

	private UnitConverter() {
	}

	/** The family a unit belongs to. */
	public enum Kind {
		/** Mass, normalised to grams. */
		WEIGHT,
		/** Volume, normalised to millilitres. */
		VOLUME,
		/** Countable items such as eggs, spring onions or spring pancakes. */
		COUNT
	}

	/**
	 * A parsed unit.
	 *
	 * @param kind family the unit belongs to
	 * @param baseUnit canonical name inside the family, one of {@code g}, {@code ml}, {@code 个}
	 * @param factor how many base units a single unit is worth
	 * @param compositeSize pack size when the unit string carried a number, e.g. {@code 500} for
	 * {@code "500g"}
	 * @param composite whether the unit string carried a pack size
	 */
	public record UnitSpec(Kind kind, String baseUnit, double factor, double compositeSize, boolean composite) {

		/** Fallback for units the converter does not understand. */
		public static final UnitSpec UNKNOWN = new UnitSpec(Kind.COUNT, "", 1.0, 0.0, false);

		public boolean isKnown() {
			return !UNKNOWN.equals(this);
		}

		public boolean isWeight() {
			return kind == Kind.WEIGHT;
		}

		public boolean isVolume() {
			return kind == Kind.VOLUME;
		}

		public boolean isCount() {
			return kind == Kind.COUNT;
		}

		/** Size of one unit expressed in the family base unit. */
		public double baseSize() {
			return composite ? compositeSize * factor : factor;
		}
	}

	/**
	 * Parse a unit string such as {@code "g"}, {@code "500g"} or {@code "10个"}.
	 *
	 * @return the parsed unit, or {@link UnitSpec#UNKNOWN} when the string is not a unit
	 */
	public static UnitSpec parse(String raw) {
		if (raw == null) {
			return UnitSpec.UNKNOWN;
		}
		String trimmed = raw.trim().toLowerCase(Locale.ROOT);
		if (trimmed.isEmpty()) {
			return UnitSpec.UNKNOWN;
		}

		int digits = 0;
		while (digits < trimmed.length()
				&& (Character.isDigit(trimmed.charAt(digits)) || trimmed.charAt(digits) == '.')) {
			digits++;
		}
		if (digits > 0 && digits < trimmed.length()) {
			String tail = trimmed.substring(digits).trim();
			UnitSpec simple = simple(tail);
			double size = parseDouble(trimmed.substring(0, digits));
			if (simple.isKnown() && size > 0) {
				return new UnitSpec(simple.kind(), simple.baseUnit(), simple.factor(), size, true);
			}
		}
		return simple(trimmed);
	}

	/**
	 * Convert an amount into the base unit of its family: grams for weights, millilitres for
	 * volumes, items for countable things.
	 *
	 * <p>Composite units are treated as a multiple of the pack they name, so {@code 1.0} of
	 * {@code "500g"} is 500 g. This is the convention the price tool uses and therefore the
	 * convention the model copies into its answer.
	 *
	 * @param quantity the amount the model reported
	 * @param unit the unit the model reported
	 * @return the amount in base units
	 */
	public static double toBaseUnits(double quantity, String unit) {
		UnitSpec spec = parse(unit);
		if (!spec.isKnown()) {
			return quantity;
		}
		return quantity * spec.baseSize();
	}

	/**
	 * Canonical display unit for a parsed unit.
	 *
	 * @return {@code g}, {@code ml} or {@code 个}
	 */
	public static String canonicalUnit(UnitSpec spec) {
		if (spec == null || !spec.isKnown()) {
			return "";
		}
		return switch (spec.kind()) {
			case WEIGHT -> "g";
			case VOLUME -> "ml";
			case COUNT -> "个";
		};
	}

	/** Whether two units can be compared, i.e. they belong to the same family. */
	public static boolean sameFamily(UnitSpec a, UnitSpec b) {
		if (a == null || b == null || !a.isKnown() || !b.isKnown()) {
			return false;
		}
		return a.kind() == b.kind();
	}

	/** Parse a unit string without throwing on malformed input. */
	private static double parseDouble(String raw) {
		try {
			return Double.parseDouble(raw);
		}
		catch (NumberFormatException ex) {
			return 0.0;
		}
	}

	/** Units without a pack size. */
	private static UnitSpec simple(String unit) {
		return switch (unit) {
			case "g", "克" -> new UnitSpec(Kind.WEIGHT, "g", 1.0, 0.0, false);
			case "kg", "千克", "公斤" -> new UnitSpec(Kind.WEIGHT, "g", 1000.0, 0.0, false);
			case "斤" -> new UnitSpec(Kind.WEIGHT, "g", 500.0, 0.0, false);
			case "两" -> new UnitSpec(Kind.WEIGHT, "g", 50.0, 0.0, false);
			case "ml", "毫升" -> new UnitSpec(Kind.VOLUME, "ml", 1.0, 0.0, false);
			case "l", "升" -> new UnitSpec(Kind.VOLUME, "ml", 1000.0, 0.0, false);
			case "个", "只", "枚", "颗", "根", "片", "张", "块", "条", "瓣", "棵", "头", "包" ->
					new UnitSpec(Kind.COUNT, "个", 1.0, 0.0, false);
			default -> UnitSpec.UNKNOWN;
		};
	}
}
