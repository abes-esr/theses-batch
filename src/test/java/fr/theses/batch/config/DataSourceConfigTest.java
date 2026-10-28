package fr.theses.batch.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires de DataSourceConfig (profil dist).
 */
class DataSourceConfigTest {

    @Test
    void dataSource_pointeSurLaBaseH2DuVolumeData() {
        DataSource dataSource = new DataSourceConfig().dataSource();

        assertThat(dataSource).isInstanceOf(DriverManagerDataSource.class);
        DriverManagerDataSource h2 = (DriverManagerDataSource) dataSource;
        assertThat(h2.getUrl()).startsWith("jdbc:h2:file:/data/batchdb");
        assertThat(h2.getUsername()).isEqualTo("sa");
    }
}
