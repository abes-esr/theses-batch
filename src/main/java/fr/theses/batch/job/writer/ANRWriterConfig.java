package fr.theses.batch.job.writer;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.job.writer.csv.ANRCsvWriter;
import fr.theses.batch.job.writer.json.ANRJsonWriter;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.CompositeItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Configuration des writers ANR
 * Permet d'activer/désactiver chaque format de sortie via configuration
 * et de centraliser la gestion des différents writers
 */
@Configuration
public class ANRWriterConfig {

    /**
     * Assemble les writers des formats listés dans app.anr.writers.enabled
     * Une valeur inconnue ou une liste vide fait échouer le démarrage plutôt que de produire un run sans sortie ;
     * un format répété n'est branché qu'une fois, sans quoi chaque ligne serait écrite en double
     */
    @Bean
    public CompositeItemWriter<ANRMatchDTO> anrCompositeWriter(
            ANRCsvWriter anrCsvWriter,
            ANRJsonWriter anrJsonWriter,
            @Value("${app.anr.writers.enabled:csv,json}") String[] enabledWriters) {

        Set<String> formats = new LinkedHashSet<>();
        Arrays.stream(enabledWriters)
                .map(format -> format.trim().toLowerCase(Locale.ROOT))
                .filter(format -> !format.isEmpty())
                .forEach(formats::add);

        if (formats.isEmpty()) {
            throw new IllegalArgumentException("app.anr.writers.enabled must list at least one format (csv, json)");
        }

        List<ItemWriter<? super ANRMatchDTO>> delegates = new ArrayList<>();
        for (String format : formats) {
            delegates.add(switch (format) {
                case "csv" -> anrCsvWriter;
                case "json" -> anrJsonWriter;
                default -> throw new IllegalArgumentException(
                        "Unknown writer in app.anr.writers.enabled: '" + format + "' (expected: csv, json)");
            });
        }

        CompositeItemWriter<ANRMatchDTO> writer = new CompositeItemWriter<>();
        writer.setDelegates(delegates);
        return writer;
    }
}
