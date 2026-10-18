package club.badernet.nestwerk.kitchen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngredientParserTest {

    @Test
    void parsesAmountUnitAndName() {
        assertThat(IngredientParser.parse("200 g Mehl")).isEqualTo(new IngredientLine(200.0, Unit.G, "Mehl", null, "200 g Mehl"));
        assertThat(IngredientParser.parse("200g Mehl").unit()).isEqualTo(Unit.G);
        assertThat(IngredientParser.parse("0,5 l Milch")).extracting(IngredientLine::amount, IngredientLine::unit,
                IngredientLine::name).containsExactly(0.5, Unit.L, "Milch");
        assertThat(IngredientParser.parse("2 EL Olivenöl")).extracting(IngredientLine::amount, IngredientLine::unit)
                .containsExactly(2.0, Unit.TBSP);
        assertThat(IngredientParser.parse("2 Dosen Tomaten")).extracting(IngredientLine::amount, IngredientLine::unit,
                IngredientLine::name).containsExactly(2.0, Unit.CAN, "Tomaten");
        assertThat(IngredientParser.parse("1 Pck. Backpulver").unit()).isEqualTo(Unit.PACKAGE);
        assertThat(IngredientParser.parse("3 Esslöffel Zucker").unit()).isEqualTo(Unit.TBSP);
    }

    @Test
    void parsesFractionsRangesAndPieces() {
        assertThat(IngredientParser.parse("1 ½ Zwiebeln")).extracting(IngredientLine::amount, IngredientLine::unit,
                IngredientLine::name).containsExactly(1.5, Unit.PIECE, "Zwiebeln");
        assertThat(IngredientParser.parse("1/2 TL Salz")).extracting(IngredientLine::amount, IngredientLine::unit)
                .containsExactly(0.5, Unit.TSP);
        assertThat(IngredientParser.parse("1-2 Knoblauchzehen")).extracting(IngredientLine::amount, IngredientLine::name)
                .containsExactly(1.0, "Knoblauchzehen");
        assertThat(IngredientParser.parse("¼ Bund Petersilie")).extracting(IngredientLine::amount, IngredientLine::unit)
                .containsExactly(0.25, Unit.BUNCH);
    }

    @Test
    void textWithoutAmountAndNotes() {
        assertThat(IngredientParser.parse("Salz")).isEqualTo(new IngredientLine(null, null, "Salz", null, "Salz"));
        assertThat(IngredientParser.parse("Prise Salz")).extracting(IngredientLine::amount, IngredientLine::unit,
                IngredientLine::name).containsExactly(1.0, Unit.PINCH, "Salz");
        assertThat(IngredientParser.parse("250 g Mehl, gesiebt")).extracting(IngredientLine::name, IngredientLine::note)
                .containsExactly("Mehl", "gesiebt");
        assertThat(IngredientParser.parse("  ")).isNull();
    }
}
