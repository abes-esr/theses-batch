package fr.theses.batch.util.parser;

import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;

/**
 * Lecture d'un PDF par blocs de taille fixe, chargés à la demande et gardés jusqu'à la fermeture.
 * <p>
 * Remplace {@code RandomAccessReadBufferedFile} pour les PDF trop gros pour être lus en entier : PDFBox
 * saute d'un objet à l'autre dans tout le fichier, et ses pages de 4 ko coûtent sur NFS un aller-retour
 * avec le serveur presque chacune. Des blocs de la taille du rsize du montage (128 Kio en production)
 * font une seule requête pleine par bloc, et un bloc n'est jamais lu deux fois : mesuré sur 11 PDF de
 * plus de 64 Mo, ≈ 470 lectures au lieu de ≈ 1 280, pour ≈ 60 Mo gardés en mémoire au plus. Les zones
 * utiles (xref, polices, flux d'objets) étant réparties dans tout le fichier, ce nombre dépend à peine
 * du nombre de pages lues.
 * <p>
 * Les vues ({@link #createView}) partagent les blocs déjà lus et ont chacune leur position ; seule la
 * lecture d'origine ferme le fichier.
 */
final class BlockCachedRandomAccessRead implements RandomAccessRead {

    private final Blocks blocks;
    private final boolean owner;
    private long position;
    private boolean closed;

    /**
     * @param channel   fichier ouvert, fermé avec cette lecture
     * @param blockSize taille des blocs lus
     */
    BlockCachedRandomAccessRead(FileChannel channel, int blockSize) throws IOException {
        this(new Blocks(channel, blockSize), true);
    }

    private BlockCachedRandomAccessRead(Blocks blocks, boolean owner) {
        this.blocks = blocks;
        this.owner = owner;
    }

    @Override
    public int read() throws IOException {
        checkClosed();
        if (position >= blocks.length) {
            return -1;
        }
        byte[] block = blocks.get(position / blocks.blockSize);
        return block[(int) (position++ % blocks.blockSize)] & 0xff;
    }

    @Override
    public int read(byte[] b, int offset, int length) throws IOException {
        checkClosed();
        if (length == 0) {
            return 0;
        }
        if (position >= blocks.length) {
            return -1;
        }
        int total = 0;
        while (total < length && position < blocks.length) {
            byte[] block = blocks.get(position / blocks.blockSize);
            int inBlock = (int) (position % blocks.blockSize);
            int n = Math.min(length - total, block.length - inBlock);
            System.arraycopy(block, inBlock, b, offset + total, n);
            total += n;
            position += n;
        }
        return total;
    }

    @Override
    public long getPosition() throws IOException {
        checkClosed();
        return position;
    }

    /** Comme les lectures de PDFBox, accepte une position au-delà de la fin et s'arrête à la fin */
    @Override
    public void seek(long newPosition) throws IOException {
        checkClosed();
        if (newPosition < 0) {
            throw new IOException("Invalid position " + newPosition);
        }
        position = Math.min(newPosition, blocks.length);
    }

    @Override
    public long length() throws IOException {
        checkClosed();
        return blocks.length;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean isEOF() throws IOException {
        checkClosed();
        return position >= blocks.length;
    }

    @Override
    public RandomAccessReadView createView(long startPosition, long streamLength) throws IOException {
        checkClosed();
        return new RandomAccessReadView(new BlockCachedRandomAccessRead(blocks, false), startPosition, streamLength,
                true);
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            if (owner) {
                blocks.close();
            }
        }
    }

    /** Nombre de blocs lus dans le fichier depuis l'ouverture */
    int blocksRead() {
        return blocks.count();
    }

    private void checkClosed() throws IOException {
        if (closed) {
            throw new IOException("BlockCachedRandomAccessRead already closed");
        }
    }

    /** Blocs déjà lus, partagés par la lecture d'origine et ses vues */
    private static final class Blocks {
        private final FileChannel channel;
        private final int blockSize;
        private final long length;
        private final Map<Long, byte[]> cache = new HashMap<>();

        Blocks(FileChannel channel, int blockSize) throws IOException {
            this.channel = channel;
            this.blockSize = blockSize;
            this.length = channel.size();
        }

        synchronized byte[] get(long index) throws IOException {
            byte[] block = cache.get(index);
            if (block == null) {
                block = read(index);
                cache.put(index, block);
            }
            return block;
        }

        /** Le dernier bloc est plus court que les autres */
        private byte[] read(long index) throws IOException {
            long start = index * blockSize;
            byte[] block = new byte[(int) Math.min(blockSize, length - start)];
            ByteBuffer buffer = ByteBuffer.wrap(block);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, start + buffer.position()) < 0) {
                    throw new IOException("Fichier raccourci pendant sa lecture (" + (start + buffer.position())
                            + " octets sur " + length + ")");
                }
            }
            return block;
        }

        synchronized int count() {
            return cache.size();
        }

        void close() throws IOException {
            channel.close();
        }
    }
}
