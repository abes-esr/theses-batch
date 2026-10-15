package fr.theses.batch.job.processor;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.client.SolrClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ANRSolrEnricherProcessorTest {

    private SolrClient solrClient;
    private ANRSolrEnricherProcessor processor;

    @BeforeEach
    void setUp() {
        solrClient = mock(SolrClient.class);
        when(solrClient.isEnabled()).thenReturn(true);
        // Les extractions de champ restent celles du vrai client
        when(solrClient.extractFieldValue(any(), anyString())).thenCallRealMethod();

        processor = new ANRSolrEnricherProcessor(solrClient);
        ReflectionTestUtils.setField(processor, "solrIdField", "id");
        ReflectionTestUtils.setField(processor, "solrNntField", "nnt");
        ReflectionTestUtils.setField(processor, "solrDoiField", "doi");
        ReflectionTestUtils.setField(processor, "solrDefenseDateField", "dateSoutenance_dt");
        processor.init();
    }

    private static ANRMatchDTO item(String filePath) {
        return new ANRMatchDTO(filePath, "these.pdf", null, 0, 0, 0.0, null, null, null, null);
    }

    private void solrRenvoie(String idStar, Map<String, Object> document) {
        when(solrClient.queryById("id", idStar, "nnt,doi,dateSoutenance_dt")).thenReturn(document);
    }

    @Test
    void solrDesactive_itemInchange() throws Exception {
        when(solrClient.isEnabled()).thenReturn(false);
        ANRMatchDTO item = item("/data/THESE_123/these.pdf");

        assertThat(processor.process(item)).isSameAs(item);
        verify(solrClient, never()).queryById(anyString(), anyString(), anyString());
    }

    @Test
    void enrichitAvecNntDoiEtDate() throws Exception {
        solrRenvoie("123", Map.of("nnt", "2018PA01", "doi", "10.1/abc", "dateSoutenance_dt", "2018-12-12"));

        ANRMatchDTO result = processor.process(item("/data/THESE_123/these.pdf"));

        assertThat(result.nnt()).isEqualTo("2018PA01");
        assertThat(result.doi()).isEqualTo("10.1/abc");
        assertThat(result.defenseDate()).isEqualTo(LocalDate.of(2018, 12, 12));
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    void dateIso8601Utc_convertieEnDateLocale() throws Exception {
        // Midi UTC : même jour quel que soit le fuseau de la JVM qui exécute le test
        solrRenvoie("123", Map.of("dateSoutenance_dt", "2018-06-15T12:00:00Z"));

        assertThat(processor.process(item("/data/THESE_123/these.pdf")).defenseDate())
                .isEqualTo(LocalDate.of(2018, 6, 15));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pas une date", "2018-13-45", "   "})
    @NullSource
    void dateAbsenteOuIllisible_laisseLaDateVide(String date) throws Exception {
        Map<String, Object> document = new HashMap<>();
        document.put("nnt", "2018PA01");
        document.put("dateSoutenance_dt", date);
        solrRenvoie("123", document);

        ANRMatchDTO result = processor.process(item("/data/THESE_123/these.pdf"));

        assertThat(result.nnt()).isEqualTo("2018PA01");
        assertThat(result.defenseDate()).isNull();
    }

    @Test
    void cheminWindows_identifiantJusquAuBackslash() throws Exception {
        solrRenvoie("ABC", Map.of("nnt", "N"));

        assertThat(processor.process(item("C:\\data\\THESE_ABC\\these.pdf")).nnt()).isEqualTo("N");
    }

    @Test
    void sansSeparateurApresThese_identifiantJusquAuBoutDuChemin() throws Exception {
        solrRenvoie("XYZ", Map.of("nnt", "N"));

        assertThat(processor.process(item("THESE_XYZ")).nnt()).isEqualTo("N");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/data/autre/these.pdf", "/data/THESE_/these.pdf", " "})
    void identifiantStarIntrouvable_itemInchange(String filePath) throws Exception {
        ANRMatchDTO item = item(filePath);

        assertThat(processor.process(item)).isSameAs(item);
        verify(solrClient, never()).queryById(anyString(), anyString(), anyString());
    }

    @Test
    void cheminNull_itemInchange() throws Exception {
        ANRMatchDTO item = item(null);

        assertThat(processor.process(item)).isSameAs(item);
    }

    @Test
    void aucunDocumentSolr_itemInchange() throws Exception {
        solrRenvoie("123", Map.of());
        ANRMatchDTO item = item("/data/THESE_123/these.pdf");

        assertThat(processor.process(item)).isSameAs(item);
    }

    @Test
    void erreurSolr_renseigneLeMessageSansLeverDException() throws Exception {
        when(solrClient.queryById(eq("id"), eq("123"), anyString())).thenThrow(new IllegalStateException("timeout"));

        ANRMatchDTO result = processor.process(item("/data/THESE_123/these.pdf"));

        assertThat(result.errorMessage()).isEqualTo("Erreur Solr: timeout");
    }
}
