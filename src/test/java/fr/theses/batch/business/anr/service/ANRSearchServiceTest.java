package fr.theses.batch.business.anr.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.regex.Pattern;

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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "*/*/temp     | ETAB/THESE_1/temp            | true",
            "*/*/temp     | ETAB/THESE_1/document        | false",
            "*/*/temp     | ETAB/THESE_1/temp/V4         | false",
            "*/*/temp     | ETAB/THESE_1/document/temp   | false",
            "*/*/temp     | ETAB/THESE_1/Temp            | false",
            "*/*/temp     | ETAB/temp                    | false",
            "**/temp      | temp                         | true",
            "**/temp      | ETAB/THESE_1/document/temp   | true",
            "**/temp      | ETAB/THESE_1/temporaire      | false",
            "*/*/te?p     | ETAB/THESE_1/temp            | true",
            "ETAB/**      | ETAB/THESE_1/temp            | true",
            "ETAB/**      | AUTRE/THESE_1                | false",
            "*/THESE_1    | ETAB/THESE_1                 | true",
            "*/THESE_1    | ETAB/THESE_10                | false",
            "a.b          | axb                          | false",
            "/*/*/temp/   | ETAB/THESE_1/temp            | true",
            "*\\*\\temp   | ETAB/THESE_1/temp            | true"
    })
    void shouldExcludeDirectory_globSurLeCheminRelatif(String excludeDirs, String relativePath, boolean expected) {
        assertThat(service.shouldExcludeDirectory(relativePath, service.parseExcludedDirectories(excludeDirs)))
                .isEqualTo(expected);
    }

    @Test
    void parseExcludedDirectories_plusieursMotifsSeparesParDesVirgules() {
        List<Pattern> patterns = service.parseExcludedDirectories(" */*/temp , ETAB/THESE_1 ,");

        assertThat(patterns).hasSize(2);
        assertThat(service.shouldExcludeDirectory("ETAB/THESE_2/temp", patterns)).isTrue();
        assertThat(service.shouldExcludeDirectory("ETAB/THESE_1", patterns)).isTrue();
        assertThat(service.shouldExcludeDirectory("ETAB/THESE_2", patterns)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " , /, "})
    void parseExcludedDirectories_valeurVide_aucunMotif(String excludeDirs) {
        assertThat(service.parseExcludedDirectories(excludeDirs)).isEmpty();
        assertThat(service.shouldExcludeDirectory("ETAB/THESE_1/temp", List.of())).isFalse();
    }

    @Test
    void parseExcludedDirectories_null_aucunMotif() {
        assertThat(service.parseExcludedDirectories(null)).isEmpty();
    }
}
