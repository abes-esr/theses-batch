package fr.theses.batch.job.datagouv.reader;

import fr.theses.batch.business.datagouv.model.Thesis;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests unitaires de ThesesItemReader.
 */
class ThesesItemReaderTest {

    private static final String BASE_URL = "http://theses.test/api/v1";

    @Test
    void read_recupereLesElementsEtTermine() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Page pleine : une page suivante est demandée, vide, ce qui termine la lecture
        expectPage(server, 0, 2, "[{\"id\":\"id1\",\"nnt\":\"NNT1\"},{\"id\":\"id2\",\"nnt\":\"NNT2\"}]", 2);
        expectPage(server, 2, 2, "[]", 2);

        ThesesItemReader reader = new ThesesItemReader(builder.build(), 2, false);

        assertThat(reader.read().id()).isEqualTo("id1");
        assertThat(reader.read().id()).isEqualTo("id2");
        assertThat(reader.read()).isNull();
        server.verify();
    }

    @Test
    void read_modeTest_limiteA50ThesesEnAjustantLaDernierePage() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // totalHits (100) non atteint : accepté en mode test
        expectPage(server, 0, 30, 30, 100);
        // Il ne reste que 20 thèses à lire avant la limite de 50
        expectPage(server, 30, 20, 20, 100);

        ThesesItemReader reader = new ThesesItemReader(builder.build(), 30, true);

        assertThat(readAll(reader)).hasSize(50);
        // Limite atteinte : plus aucun appel à l'API
        assertThat(reader.read()).isNull();
        server.verify();
    }

    @Test
    void read_dernierePageIncomplete_arreteSansNouvelAppel() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectPage(server, 0, 10, 3, 3);

        ThesesItemReader reader = new ThesesItemReader(builder.build(), 10, false);

        assertThat(readAll(reader)).extracting(Thesis::id).containsExactly("id0", "id1", "id2");
        assertThat(reader.read()).isNull();
        server.verify();
    }

    @Test
    void read_moinsDeThesesQueTotalHits_echoue() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // L'API annonce 100 thèses mais la page s'arrête à 3 : export tronqué
        expectPage(server, 0, 10, 3, 100);

        ThesesItemReader reader = new ThesesItemReader(builder.build(), 10, false);

        assertThatThrownBy(() -> readAll(reader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 thèses distinctes lues sur 100");
    }

    @Test
    void read_thesesAjouteesPendantLExport_acceptees() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // totalHits = 2 à la première page, une thèse est apparue avant la page suivante
        expectPage(server, 0, 2, 2, 2);
        expectPage(server, 2, 2, 1, 3);

        ThesesItemReader reader = new ThesesItemReader(builder.build(), 2, false);

        assertThat(readAll(reader)).hasSize(3);
        server.verify();
    }

    @Test
    void read_theseRepeteeSurDeuxPages_ignoreLeDoublonParNnt() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // L'ordre a bougé entre les pages : NNT2 revient une seconde fois
        expectPage(server, 0, 2, "[{\"id\":\"a\",\"nnt\":\"NNT1\"},{\"id\":\"b\",\"nnt\":\"NNT2\"}]", 3);
        expectPage(server, 2, 2, "[{\"id\":\"b\",\"nnt\":\"NNT2\"},{\"id\":\"c\",\"nnt\":\"NNT3\"}]", 3);
        expectPage(server, 4, 2, "[]", 3);

        ThesesItemReader reader = new ThesesItemReader(builder.build(), 2, false);

        assertThat(readAll(reader)).extracting(Thesis::nnt).containsExactly("NNT1", "NNT2", "NNT3");
        server.verify();
    }

    @Test
    void read_reponseSansCorps_echoueCarExportVide() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(pageUri(0, 10))).andRespond(withNoContent());

        ThesesItemReader reader = new ThesesItemReader(builder.build(), 10, false);

        assertThatThrownBy(reader::read)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Export vide");
        server.verify();
    }

    @Test
    void read_modeTestSansThese_retourneNull() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(pageUri(0, 10))).andRespond(withNoContent());

        ThesesItemReader reader = new ThesesItemReader(builder.build(), 10, true);

        assertThat(reader.read()).isNull();
        assertThat(reader.read()).isNull();
        server.verify();
    }

    @Test
    void read_reponseSansListeDeTheses_echoueCarExportVide() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(pageUri(0, 10)))
                .andRespond(withSuccess("{\"totalHits\":0}", MediaType.APPLICATION_JSON));

        ThesesItemReader reader = new ThesesItemReader(builder.build(), 10, false);

        assertThatThrownBy(reader::read)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Export vide");
        server.verify();
    }

    private static List<Thesis> readAll(ThesesItemReader reader) {
        List<Thesis> result = new ArrayList<>();
        for (Thesis t = reader.read(); t != null; t = reader.read()) {
            result.add(t);
        }
        return result;
    }

    private static void expectPage(MockRestServiceServer server, int debut, int nombre, int size, int totalHits) {
        String theses = IntStream.range(debut, debut + size)
                .mapToObj(i -> "{\"id\":\"id" + i + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
        expectPage(server, debut, nombre, theses, totalHits);
    }

    private static void expectPage(MockRestServiceServer server, int debut, int nombre, String theses, int totalHits) {
        server.expect(requestTo(pageUri(debut, nombre)))
                .andRespond(withSuccess("{\"totalHits\":" + totalHits + ",\"theses\":" + theses + "}", MediaType.APPLICATION_JSON));
    }

    private static URI pageUri(int debut, int nombre) {
        return UriComponentsBuilder.fromUriString(BASE_URL)
                .path("/theses/recherche/")
                .queryParam("q", "*")
                .queryParam("debut", debut)
                .queryParam("nombre", nombre)
                .queryParam("tri", "dateAsc")
                .queryParam("filtres", "[Statut=\"soutenue\"]")
                .queryParam("viewFull", "true")
                .encode()
                .build()
                .toUri();
    }
}
