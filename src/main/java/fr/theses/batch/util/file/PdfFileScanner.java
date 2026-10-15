package fr.theses.batch.util.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.function.Predicate;

/**
 * Parcours parallèle d'une arborescence de fichiers.
 * <p>
 * Chaque répertoire est listé par une tâche distincte : sur un partage réseau (NFS, SMB), le temps
 * se passe à attendre les réponses du serveur, et plusieurs listages simultanés recouvrent ces attentes.
 * Chaque entrée n'est interrogée qu'une fois (attributs lus sans suivre les liens), là où
 * {@code Files.walk} suivi de {@code Files.isRegularFile} en faisait deux.
 * <p>
 * Même périmètre que {@code Files.walk} sans option : les liens symboliques vers des répertoires
 * ne sont pas parcourus, un lien vers un fichier régulier est retenu.
 */
public final class PdfFileScanner {

    private PdfFileScanner() {
    }

    /**
     * Liste les fichiers réguliers de l'arborescence dont le nom satisfait le filtre.
     *
     * @param root           Répertoire racine
     * @param fileNameFilter Filtre sur le nom du fichier (sans le chemin)
     * @param threads        Nombre de répertoires listés simultanément
     * @return Chemins trouvés, dans un ordre quelconque
     * @throws IOException Si un répertoire ne peut pas être lu
     */
    public static List<Path> scan(Path root, Predicate<String> fileNameFilter, int threads) throws IOException {
        Queue<Path> found = new ConcurrentLinkedQueue<>();
        try (ForkJoinPool pool = new ForkJoinPool(Math.max(1, threads))) {
            pool.invoke(new DirectoryTask(root, fileNameFilter, found));
        } catch (RuntimeException e) {
            throw unwrapIOException(e);
        }
        return new ArrayList<>(found);
    }

    /**
     * ForkJoinPool peut réemballer l'exception levée dans un autre thread : on remonte la chaîne
     * des causes pour rendre l'IOException d'origine.
     */
    private static IOException unwrapIOException(RuntimeException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException ioException) {
                return ioException;
            }
        }
        throw e;
    }

    private static final class DirectoryTask extends RecursiveAction {

        private final Path directory;
        private final Predicate<String> fileNameFilter;
        private final Queue<Path> found;

        private DirectoryTask(Path directory, Predicate<String> fileNameFilter, Queue<Path> found) {
            this.directory = directory;
            this.fileNameFilter = fileNameFilter;
            this.found = found;
        }

        @Override
        protected void compute() {
            List<DirectoryTask> subdirectories = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) {
                    BasicFileAttributes attributes =
                            Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isDirectory()) {
                        subdirectories.add(new DirectoryTask(entry, fileNameFilter, found));
                    } else if (fileNameFilter.test(entry.getFileName().toString())
                            && isRegularFile(entry, attributes)) {
                        found.add(entry);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            invokeAll(subdirectories);
        }

        private static boolean isRegularFile(Path entry, BasicFileAttributes attributes) {
            // Seul un lien symbolique demande un second appel, pour connaître sa cible
            return attributes.isRegularFile() || attributes.isSymbolicLink() && Files.isRegularFile(entry);
        }
    }
}
