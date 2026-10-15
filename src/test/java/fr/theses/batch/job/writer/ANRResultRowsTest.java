package fr.theses.batch.job.writer;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.business.anr.model.dto.ANRPageMatchDTO;
import fr.theses.batch.job.RunTimestamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ANRResultRowsTest {

    @TempDir
    Path outputDir;

    private ANRResultRows rows(String header) {
        return new ANRResultRows(new RunTimestamp("2026-01-02-03-04-05"), outputDir.toString(), header);
    }

    private static ANRMatchDTO item(List<ANRPageMatchDTO> pages, String error, LocalDate date) {
        return new ANRMatchDTO("/d/a.pdf", "a.pdf", pages, 1, 1, 0.1, error, null, null, date);
    }

    private static final ANRPageMatchDTO P1 = new ANRPageMatchDTO(1, "ANR-1", "", "");
    private static final ANRPageMatchDTO P2 = new ANRPageMatchDTO(2, "ANR-2", "", "");

    @Test
    void fieldNames_dansLOrdreDuHeaderEspacesIgnores() {
        assertThat(rows(" match_value , PAGE_NUMBER,file_name").fieldNames())
                .containsExactly("match_value", "page_number", "file_name");
    }

    @Test
    void headerVideOuNull_aucuneColonne() {
        assertThat(rows("").fieldNames()).isEmpty();
        assertThat(rows(null).fieldNames()).isEmpty();
    }

    @Test
    void headerInconnu_echoueALaConstruction() {
        assertThatThrownBy(() -> rows("file_name,inconnu")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toRows_uneLigneParCorrespondance() {
        List<Map<String, Object>> result = rows("page_number,match_value").toRows(item(List.of(P1, P2), null, null));

        assertThat(result).containsExactly(
                Map.of("page_number", 1, "match_value", "ANR-1"),
                Map.of("page_number", 2, "match_value", "ANR-2"));
        assertThat(result.getFirst().keySet()).containsExactly("page_number", "match_value");
    }

    @Test
    void toRows_sansCorrespondanceNiErreur_uneLigneVide() {
        List<Map<String, Object>> result = rows("match_value,error_message").toRows(item(List.of(), "", null));

        assertThat(result).containsExactly(Map.of("match_value", "", "error_message", ""));
    }

    @Test
    void toRows_erreurSansCorrespondance_seuleLaLigneDErreur() {
        List<Map<String, Object>> result = rows("error_message").toRows(item(List.of(), "boom", null));

        assertThat(result).containsExactly(Map.of("error_message", "boom"));
    }

    @Test
    void toRows_erreurAvecCorrespondances_ligneDErreurEnPlus() {
        List<Map<String, Object>> result = rows("match_value,error_message").toRows(item(List.of(P1), "boom", null));

        assertThat(result).containsExactly(
                Map.of("match_value", "ANR-1", "error_message", ""),
                Map.of("match_value", "", "error_message", "boom"));
    }

    @Test
    void groupByYear_parAnneeDeSoutenanceOuUnknown() {
        ANRMatchDTO a2018 = item(List.of(), null, LocalDate.of(2018, 1, 1));
        ANRMatchDTO b2018 = item(List.of(), null, LocalDate.of(2018, 12, 31));
        ANRMatchDTO sansDate = item(List.of(), null, null);

        Map<String, List<ANRMatchDTO>> groups = rows("").groupByYear(List.of(a2018, sansDate, b2018));

        assertThat(groups).containsOnlyKeys("2018", "unknown");
        assertThat(groups.get("2018")).containsExactly(a2018, b2018);
        assertThat(groups.get("unknown")).containsExactly(sansDate);
    }

    @Test
    void resultFile_prefixeParLHorodatageDuRunEtCreeLeRepertoire() throws IOException {
        ANRResultRows resultRows = new ANRResultRows(new RunTimestamp("2026-01-02-03-04-05"),
                outputDir.resolve("sous/rep").toString(), "");

        Path file = resultRows.resultFile("2018", "csv");

        assertThat(file.getFileName().toString()).isEqualTo("2026-01-02-03-04-05_anr_results_2018.csv");
        assertThat(file.getParent()).isEqualTo(outputDir.resolve("sous/rep"));
        assertThat(Files.isDirectory(file.getParent())).isTrue();
        assertThat(file).doesNotExist();
    }
}
