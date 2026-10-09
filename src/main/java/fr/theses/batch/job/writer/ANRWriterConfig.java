package fr.theses.batch.job.writer;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.job.writer.csv.CsvWriter;
import fr.theses.batch.job.writer.json.JsonWriter;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.CompositeItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration des writers ANR
 * Permet d'activer/désactiver chaque format de sortie via configuration
 * et de centraliser la gestion des différents writers
 */
@Configuration
public class ANRWriterConfig {

    @Bean
    public CompositeItemWriter<ANRMatchDTO> anrCompositeWriter(
            CsvWriter csvWriter,
            JsonWriter jsonWriter,
            @Value("${app.anr.writers.enabled:csv,json}") String[] enabledWriters) {

        List<ItemWriter<? super ANRMatchDTO>> delegates = new ArrayList<>();

        // Ajouter les writers activés via la configuration
        for (String writer : enabledWriters) {
            switch (writer.trim().toLowerCase()) {
                case "csv":
                    delegates.add((ItemWriter<? super ANRMatchDTO>) csvWriter);
                    break;
                case "json":
                    delegates.add((ItemWriter<? super ANRMatchDTO>) jsonWriter);
                    break;
                default:
                    // Ignorer les valeurs inconnues
                    break;
            }
        }

        CompositeItemWriter<ANRMatchDTO> writer = new CompositeItemWriter<>();
        writer.setDelegates(delegates);
        return writer;
    }
}
