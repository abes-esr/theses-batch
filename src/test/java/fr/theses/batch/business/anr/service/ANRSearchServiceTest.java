package fr.theses.batch.business.anr.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ANRSearchServiceTest {

    private final ANRSearchService service = new ANRSearchService();

    private static final List<String> KEYWORDS = List.of("annexe", "Résumé", "cover");

    @ParameterizedTest
    @CsvSource({
            "these.pdf, false",
            "THESE.PDF, false",
            "these_annexe.pdf, true",
            "THESE_ANNEXES.PDF, true",
            "these_résumé.pdf, true",
            "Cover-page.pdf, true",
            "these.txt, true",
            "these.pdf.bak, true"
    })
    void shouldExcludeFile_filtreExtensionEtMotsCles(String fileName, boolean expected) {
        assertThat(service.shouldExcludeFile(fileName, KEYWORDS)).isEqualTo(expected);
    }

    @Test
    void shouldExcludeFile_excluLeNomNull() {
        assertThat(service.shouldExcludeFile(null, KEYWORDS)).isTrue();
    }

    @Test
    void shouldExcludeFile_conserveToutPdfSansMotCle() {
        assertThat(service.shouldExcludeFile("annexe.pdf", List.of())).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "/data/THESE_1/these.pdf, these.pdf",
            "C:\\data\\THESE_1\\these.pdf, these.pdf",
            "C:\\data/mixte\\these.pdf, these.pdf",
            "these.pdf, these.pdf"
    })
    void extractFileName_gereLesDeuxSeparateurs(String filePath, String expected) {
        assertThat(service.extractFileName(filePath)).isEqualTo(expected);
    }

    @Test
    void extractFileName_renvoieVidePourNull() {
        assertThat(service.extractFileName(null)).isEmpty();
    }
}
