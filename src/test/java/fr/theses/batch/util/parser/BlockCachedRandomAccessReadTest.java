package fr.theses.batch.util.parser;

import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockCachedRandomAccessReadTest {

    private static final int BLOCK = 100;

    @TempDir
    Path tempDir;

    /** 1 050 octets : 10 blocs pleins et un dernier de 50 */
    private final byte[] content = new byte[1050];

    {
        new Random(42).nextBytes(content);
    }

    private BlockCachedRandomAccessRead open() throws IOException {
        Path file = Files.write(tempDir.resolve("a.pdf"), content);
        return new BlockCachedRandomAccessRead(FileChannel.open(file), BLOCK);
    }

    @Test
    void litOctetParOctetCommeEnMemoire() throws IOException {
        try (RandomAccessRead blocks = open(); RandomAccessRead memory = new RandomAccessReadBuffer(content)) {
            assertThat(blocks.length()).isEqualTo(memory.length());
            for (int i = 0; i <= content.length; i++) {
                assertThat(blocks.isEOF()).isEqualTo(memory.isEOF());
                assertThat(blocks.read()).isEqualTo(memory.read());
            }
            assertThat(blocks.read()).isEqualTo(-1);
        }
    }

    @Test
    void lectureQuiChevaucheDesBlocs() throws IOException {
        try (BlockCachedRandomAccessRead input = open()) {
            input.seek(95);
            byte[] b = new byte[220];

            assertThat(input.read(b, 0, b.length)).isEqualTo(220);
            assertThat(b).isEqualTo(Arrays.copyOfRange(content, 95, 315));
            assertThat(input.getPosition()).isEqualTo(315);
            assertThat(input.blocksRead()).isEqualTo(4);
        }
    }

    @Test
    void lectureQuiDepasseLaFin_tronquee_puisMoinsUn() throws IOException {
        try (RandomAccessRead input = open()) {
            input.seek(1040);
            byte[] b = new byte[30];

            assertThat(input.read(b, 5, 30)).isEqualTo(10);
            assertThat(Arrays.copyOfRange(b, 5, 15)).isEqualTo(Arrays.copyOfRange(content, 1040, 1050));
            assertThat(input.read(b, 0, 30)).isEqualTo(-1);
            assertThat(input.read(b, 0, 0)).isZero();
        }
    }

    @Test
    void seekAuDelaDeLaFin_positionneALaFin_seekNegatif_refuse() throws IOException {
        try (RandomAccessRead input = open()) {
            input.seek(5000);

            assertThat(input.getPosition()).isEqualTo(1050);
            assertThat(input.isEOF()).isTrue();
            assertThatThrownBy(() -> input.seek(-1)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void chaqueBlocLuUneSeuleFois() throws IOException {
        try (BlockCachedRandomAccessRead input = open()) {
            for (int pass = 0; pass < 3; pass++) {
                input.seek(0);
                while (input.read() != -1) {
                    // lecture complète
                }
            }

            assertThat(input.blocksRead()).isEqualTo(11);
        }
    }

    @Test
    void vues_partagentLesBlocs_positionPropre_neFermentPasLeFichier() throws IOException {
        try (BlockCachedRandomAccessRead input = open()) {
            input.seek(10);
            try (RandomAccessRead view = input.createView(250, 100)) {
                assertThat(view.read()).isEqualTo(content[250] & 0xff);
                view.seek(99);
                assertThat(view.read()).isEqualTo(content[349] & 0xff);
                assertThat(view.read()).isEqualTo(-1);
            }

            assertThat(input.getPosition()).isEqualTo(10);
            assertThat(input.read()).isEqualTo(content[10] & 0xff);
            assertThat(input.blocksRead()).isEqualTo(3);
        }
    }

    @Test
    void fermeture_fermeLeFichier_etInterditLesLectures() throws IOException {
        Path file = Files.write(tempDir.resolve("a.pdf"), content);
        FileChannel channel = FileChannel.open(file);
        BlockCachedRandomAccessRead input = new BlockCachedRandomAccessRead(channel, BLOCK);

        input.close();
        input.close();

        assertThat(input.isClosed()).isTrue();
        assertThat(channel.isOpen()).isFalse();
        assertThatThrownBy(input::read).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> input.seek(0)).isInstanceOf(IOException.class);
        assertThatThrownBy(input::length).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> input.createView(0, 1)).isInstanceOf(IOException.class);
    }

    @Test
    void fichierRaccourciPendantLaLecture_leveIOException() throws IOException {
        try (RandomAccessRead input = open()) {
            try (FileChannel writer = FileChannel.open(tempDir.resolve("a.pdf"), StandardOpenOption.WRITE)) {
                writer.truncate(500);
            }
            input.seek(900);

            assertThatThrownBy(input::read).isInstanceOf(IOException.class).hasMessageContaining("raccourci");
        }
    }
}
