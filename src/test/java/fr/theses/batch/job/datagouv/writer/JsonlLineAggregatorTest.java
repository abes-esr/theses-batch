package fr.theses.batch.job.datagouv.writer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import fr.theses.batch.business.datagouv.model.ExportPerson;
import fr.theses.batch.business.datagouv.model.ExportThesis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de JsonlLineAggregator.
 */
class JsonlLineAggregatorTest {

    private JsonlLineAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new JsonlLineAggregator(new ObjectMapper());
    }

    @Test
    void aggregate_transformeEnLigneJsonUnique() {
        ExportThesis export = new ExportThesis(
                "oui",
                new ExportPerson("Curie", "Marie", "123456789"),
                null, null, "PARIS", "1903-06-25", null, "Physique",
                null, null, null, null, null, null, "1903PARIS001",
                null, null, null, null, null, "theses.fr",
                "soutenue", null, null, "non", null, null, null
        );

        String jsonLine = aggregator.aggregate(export);

        assertThat(jsonLine).isNotNull();
        assertThat(jsonLine).doesNotContain("\n");
        assertThat(jsonLine).contains("\"accessible\":\"oui\"");
        assertThat(jsonLine).contains("\"nom\":\"Curie\"");
        assertThat(jsonLine).contains("\"nnt\":\"1903PARIS001\"");
    }

    @Test
    void aggregate_erreurDeSerialisation_leveIllegalStateException() {
        ObjectMapper mapper = mock(ObjectMapper.class);
        JacksonException cause = mock(JacksonException.class);
        when(mapper.writeValueAsString(any())).thenThrow(cause);
        JsonlLineAggregator failing = new JsonlLineAggregator(mapper);
        ExportThesis export = minimalExport();

        assertThatThrownBy(() -> failing.aggregate(export))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Erreur de sérialisation")
                .hasCause(cause);
    }

    @Test
    void aggregate_serialisationNulle_leveIllegalStateException() {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenReturn(null);
        JsonlLineAggregator failing = new JsonlLineAggregator(mapper);
        ExportThesis export = minimalExport();

        assertThatThrownBy(() -> failing.aggregate(export))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valeur nulle");
    }

    private static ExportThesis minimalExport() {
        return new ExportThesis(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null
        );
    }
}
