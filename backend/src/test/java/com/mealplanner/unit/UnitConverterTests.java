package com.mealplanner.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mealplanner.unit.UnitConverter.Kind;
import com.mealplanner.unit.UnitConverter.UnitSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The unit strings that reach the backend come from a language model, so the converter has to
 * understand every shape it uses: base units, Chinese unit words and the pack sizes of the price
 * database.
 */
class UnitConverterTests {

	@Test
	@DisplayName("基础单位解析出单位族和换算系数")
	void parsesBaseUnits() {
		UnitSpec gram = UnitConverter.parse("g");
		assertThat(gram.kind()).isEqualTo(Kind.WEIGHT);
		assertThat(gram.factor()).isEqualTo(1.0);
		assertThat(gram.composite()).isFalse();

		assertThat(UnitConverter.parse("kg").factor()).isEqualTo(1000.0);
		assertThat(UnitConverter.parse("毫升").kind()).isEqualTo(Kind.VOLUME);
		assertThat(UnitConverter.parse("L").factor()).isEqualTo(1000.0);
		assertThat(UnitConverter.parse("根").kind()).isEqualTo(Kind.COUNT);
	}

	@Test
	@DisplayName("包装规格解析成包大小和基础单位")
	void parsesPackSizes() {
		UnitSpec pack = UnitConverter.parse("500g");

		assertThat(pack.composite()).isTrue();
		assertThat(pack.compositeSize()).isEqualTo(500.0);
		assertThat(pack.kind()).isEqualTo(Kind.WEIGHT);
		assertThat(pack.baseSize()).isEqualTo(500.0);

		assertThat(UnitConverter.parse("10个").baseSize()).isEqualTo(10.0);
		assertThat(UnitConverter.parse("1L").baseSize()).isEqualTo(1000.0);
		assertThat(UnitConverter.parse("2根").baseSize()).isEqualTo(2.0);
	}

	@Test
	@DisplayName("换算到基础单位：克、千克、毫升、升")
	void convertsToBaseUnits() {
		assertThat(UnitConverter.toBaseUnits(300, "g")).isEqualTo(300.0);
		assertThat(UnitConverter.toBaseUnits(1.25, "kg")).isEqualTo(1250.0);
		assertThat(UnitConverter.toBaseUnits(15, "ml")).isEqualTo(15.0);
		assertThat(UnitConverter.toBaseUnits(0.5, "L")).isEqualTo(500.0);
	}

	@Test
	@DisplayName("包装规格按倍数换算：1 份 500g 就是 500 克")
	void convertsPackSizes() {
		assertThat(UnitConverter.toBaseUnits(1.0, "500g")).isEqualTo(500.0);
		assertThat(UnitConverter.toBaseUnits(0.4, "10个")).isEqualTo(4.0);
		assertThat(UnitConverter.toBaseUnits(0.15, "100g")).isEqualTo(15.0);
	}

	@Test
	@DisplayName("无法识别的单位保持原值，不会被悄悄换算")
	void keepsUnknownUnits() {
		assertThat(UnitConverter.parse("少许").isKnown()).isFalse();
		assertThat(UnitConverter.toBaseUnits(2, "少许")).isEqualTo(2.0);
		assertThat(UnitConverter.toBaseUnits(2, null)).isEqualTo(2.0);
	}

	@Test
	@DisplayName("单位族不同的两个单位不可比较")
	void detectsDifferentFamilies() {
		assertThat(UnitConverter.sameFamily(UnitConverter.parse("g"), UnitConverter.parse("500g"))).isTrue();
		assertThat(UnitConverter.sameFamily(UnitConverter.parse("g"), UnitConverter.parse("ml"))).isFalse();
		assertThat(UnitConverter.sameFamily(UnitConverter.parse("个"), UnitConverter.parse("500g"))).isFalse();
	}

	@Test
	@DisplayName("基础单位名称归一化")
	void canonicalisesUnitNames() {
		assertThat(UnitConverter.canonicalUnit(UnitConverter.parse("克"))).isEqualTo("g");
		assertThat(UnitConverter.canonicalUnit(UnitConverter.parse("毫升"))).isEqualTo("ml");
		assertThat(UnitConverter.canonicalUnit(UnitConverter.parse("根"))).isEqualTo("个");
	}
}
