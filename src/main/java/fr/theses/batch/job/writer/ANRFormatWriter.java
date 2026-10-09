package fr.theses.batch.job.writer;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import org.springframework.batch.infrastructure.item.ItemWriter;

import java.util.List;

/**
 * Interface commune pour tous les writers de format ANR
 * Permet une approche modulaire où chaque writer gère un seul format de sortie
 */
public interface ANRFormatWriter extends ItemWriter<ANRMatchDTO> {

    /**
     * Méthode utilitaire pour écrire une liste d'items
     * @param items la liste des DTO à écrire
     * @throws Exception en cas d'erreur d'écriture
     */
    default void write(List<? extends ANRMatchDTO> items) throws Exception {
        this.write(new org.springframework.batch.infrastructure.item.Chunk<>(items));
    }
}
