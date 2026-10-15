package fr.theses.batch.business.anr.model.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ANRMatchDTOTest {

    @Test
    void pageMatchesNullDevientUneListeVide() {
        ANRMatchDTO dto = new ANRMatchDTO("/a.pdf", "a.pdf", null, 0, 0, 0.0, null, null, null, null);

        assertThat(dto.pageMatches()).isNotNull().isEmpty();
    }

    @Test
    void pageMatchesEstUneCopieDefensive() {
        List<ANRPageMatchDTO> source = new ArrayList<>();
        source.add(new ANRPageMatchDTO(1, "ANR-15-CE23-0001", "avant", "après"));

        ANRMatchDTO dto = new ANRMatchDTO("/a.pdf", "a.pdf", source, 1, 2, 0.5, null, null, null, null);
        source.clear();

        assertThat(dto.pageMatches()).hasSize(1);
    }

    @Test
    void withProduitUneNouvelleInstanceSansModifierLOriginal() {
        ANRMatchDTO dto = new ANRMatchDTO("/a.pdf", "a.pdf", List.of(), 0, 0, 0.0, null, null, null, null);

        ANRMatchDTO enriched = dto.withNnt("2020PA01").withDefenseDate(LocalDate.of(2020, 1, 2));

        assertThat(dto.nnt()).isNull();
        assertThat(enriched.nnt()).isEqualTo("2020PA01");
        assertThat(enriched.defenseDate()).isEqualTo(LocalDate.of(2020, 1, 2));
        assertThat(enriched.filePath()).isEqualTo("/a.pdf");
    }
}
