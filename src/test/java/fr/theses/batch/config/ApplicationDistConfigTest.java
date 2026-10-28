package fr.theses.batch.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests de application-dist.yml (profil des conteneurs Docker).
 * Le conteneur datagouv ne reçoit aucune variable ANR ni Solr : toutes les propriétés doivent
 * pouvoir se résoudre sans variable d'environnement, sinon l'application ne démarre pas.
 */
class ApplicationDistConfigTest {

    private StandardEnvironment environment;
    private EnumerablePropertySource<?> distYml;

    @BeforeEach
    void setUp() throws IOException {
        environment = new StandardEnvironment();
        // Ni variables d'environnement ni propriétés système : comme un conteneur sans .env
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application-dist", new ClassPathResource("application-dist.yml"));
        distYml = (EnumerablePropertySource<?>) sources.getFirst();
        environment.getPropertySources().addFirst(distYml);
    }

    @Test
    void toutesLesProprietes_seResolventSansVariableDEnvironnement() {
        for (String name : distYml.getPropertyNames()) {
            assertThatCode(() -> environment.getRequiredProperty(name))
                    .as("propriété %s", name)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void motifAnrParDefaut_estLuTelQuel() {
        // Le défaut contient des « : » et des accolades, interprétés par Spring dans ${VAR:défaut}
        assertThat(environment.getProperty("app.anr.pattern")).isEqualTo(
                "ANR-(?:\\d{2}-)?[A-Za-z0-9]{4,8}(?:-\\d{1,4})?|Agence nationale de la recherche|Labex|\\bPIA\\b");
    }

    @Test
    void urlApiTheses_sansValeurParDefaut() {
        assertThat(environment.getProperty("app.theses.api-base-url")).isEmpty();
    }
}
