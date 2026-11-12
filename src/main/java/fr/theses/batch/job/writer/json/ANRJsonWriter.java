package fr.theses.batch.job.writer.json;

import fr.theses.batch.business.anr.model.dto.ANRMatchDTO;
import fr.theses.batch.job.writer.ANRResultRows;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writer pour l'export JSON des résultats ANR
 * Les champs et les lignes viennent d'ANRResultRows ; ce writer ne fait que les sérialiser en JSON
 * <p>
 * Chaque fichier est un tableau JSON complété en place, chunk après chunk : les éléments du chunk sont
 * écrits juste avant le crochet fermant, que l'on réécrit derrière eux. Le fichier reste ainsi un tableau
 * valide après chaque chunk, même si le run s'interrompt, sans garder en mémoire les résultats
 * déjà écrits ni réécrire le fichier entier. Le contenu est celui qu'écrirait Jackson pour le tableau
 * complet : délimiteurs et séparateurs sont ceux de son pretty printer par défaut (ou du mode compact).
 */
@Component
public class ANRJsonWriter implements ItemWriter<ANRMatchDTO> {

    private final ANRResultRows resultRows;
    private final ObjectWriter elementWriter;
    private final byte[] arrayStart;
    private final byte[] separator;
    private final byte[] arrayEnd;

    /** Années dont le fichier a déjà été créé pendant ce run : on y ajoute, on ne l'écrase plus. */
    private final Set<String> startedYears = new HashSet<>();

    public ANRJsonWriter(
            ANRResultRows resultRows,
            @Value("${app.anr.json.pretty-print:true}") boolean prettyPrint) {
        this.resultRows = resultRows;
        ObjectMapper objectMapper = new ObjectMapper();
        this.elementWriter = prettyPrint ? objectMapper.writerWithDefaultPrettyPrinter() : objectMapper.writer();
        this.arrayStart = ascii(prettyPrint ? "[ " : "[");
        this.separator = ascii(prettyPrint ? ", " : ",");
        this.arrayEnd = ascii(prettyPrint ? " ]" : "]");
    }

    /**
     * Indique si le fichier d'erreurs a été créé
     */
    private boolean errorFileStarted = false;

    @Override
    public void write(Chunk<? extends ANRMatchDTO> chunk) throws IOException {
        // Séparer les items avec erreur des items normaux
        List<ANRMatchDTO> errorItems = (List<ANRMatchDTO>) chunk.getItems().stream()
                .filter(item -> item.errorMessage() != null && !item.errorMessage().isEmpty())
                .toList();
        List<ANRMatchDTO> normalItems = (List<ANRMatchDTO>) chunk.getItems().stream()
                .filter(item -> item.errorMessage() == null || item.errorMessage().isEmpty())
                .toList();

        // Écrire les erreurs dans un fichier dédié (toutes années confondues)
        if (!errorItems.isEmpty()) {
            writeErrorItems(errorItems);
        }

        // Écrire les items normaux dans les fichiers par année
        for (Map.Entry<String, List<ANRMatchDTO>> entry : resultRows.groupByYear(normalItems).entrySet()) {
            String year = entry.getKey();
            // Fichier commun à tout le run : les chunks suivants y sont ajoutés
            Path jsonFilePath = resultRows.resultFile(year, "json");

            boolean firstWrite = !startedYears.contains(year);
            ByteArrayOutputStream elements = new ByteArrayOutputStream();
            for (ANRMatchDTO item : entry.getValue()) {
                ANRResultRows.Pair<List<Map<String, Object>>, List<Map<String, Object>>> separated = 
                        resultRows.toRowsSeparated(item);
                for (Map<String, Object> row : separated.first) {
                    if (!firstWrite || elements.size() > 0) {
                        elements.write(separator);
                    }
                    elements.write(elementWriter.writeValueAsBytes(row));
                }
            }
            if (elements.size() == 0) {
                continue;
            }

            if (firstWrite) {
                create(jsonFilePath, elements);
                startedYears.add(year);
            } else {
                append(jsonFilePath, elements);
            }
        }
    }

    /**
     * Écrit les items avec erreur dans un fichier JSON dédié
     */
    private void writeErrorItems(List<ANRMatchDTO> errorItems) throws IOException {
        Path errorFilePath = resultRows.errorFile("json");
        boolean firstWrite = !errorFileStarted;
        ByteArrayOutputStream elements = new ByteArrayOutputStream();

        for (ANRMatchDTO item : errorItems) {
            ANRResultRows.Pair<List<Map<String, Object>>, List<Map<String, Object>>> separated = 
                    resultRows.toRowsSeparated(item);
            for (Map<String, Object> row : separated.second) {
                if (!firstWrite || elements.size() > 0) {
                    elements.write(separator);
                }
                elements.write(elementWriter.writeValueAsBytes(row));
            }
        }

        if (elements.size() == 0) {
            return;
        }

        if (firstWrite) {
            create(errorFilePath, elements);
            errorFileStarted = true;
        } else {
            append(errorFilePath, elements);
        }
    }

    private void create(Path file, ByteArrayOutputStream elements) throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream(arrayStart.length + elements.size() + arrayEnd.length);
        content.write(arrayStart);
        elements.writeTo(content);
        content.write(arrayEnd);
        Files.write(file, content.toByteArray());
    }

    /**
     * Écrit les éléments à la place du crochet fermant, puis le réécrit derrière eux.
     * Le fichier doit se terminer par le crochet écrit par ce writer : sinon il a été modifié
     * pendant le run, et on s'arrête plutôt que de produire un JSON invalide.
     */
    private void append(Path file, ByteArrayOutputStream elements) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long endPosition = channel.size() - arrayEnd.length;
            ByteBuffer tail = ByteBuffer.allocate(arrayEnd.length);
            if (endPosition < arrayStart.length || channel.read(tail, endPosition) != arrayEnd.length
                    || !Arrays.equals(tail.array(), arrayEnd)) {
                throw new IOException("Fichier JSON modifié pendant le run, fin de tableau introuvable : " + file);
            }
            ByteArrayOutputStream content = new ByteArrayOutputStream(elements.size() + arrayEnd.length);
            elements.writeTo(content);
            content.write(arrayEnd);
            ByteBuffer buffer = ByteBuffer.wrap(content.toByteArray());
            long position = endPosition;
            while (buffer.hasRemaining()) {
                position += channel.write(buffer, position);
            }
        }
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
