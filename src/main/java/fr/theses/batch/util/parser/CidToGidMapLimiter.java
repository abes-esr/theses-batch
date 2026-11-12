package fr.theses.batch.util.parser;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.DecodeOptions;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Borne la table /CIDToGIDMap des polices composites avant que PDFBox ne la lise.
 * <p>
 * PDFBox décode cette table entière en mémoire, sans limite ({@code PDCIDFont.readCIDToGIDMap}). Un CID
 * tient sur 2 octets : une table utile ne dépasse donc jamais 65 536 entrées de 2 octets, soit 128 Kio.
 * Un flux corrompu peut pourtant se décoder en plus d'1 Go (2016PA066214.pdf en production) et épuiser
 * le heap : l'{@link OutOfMemoryError} n'est pas une exception, elle fait échouer tout le step.
 * <p>
 * La table est donc décodée ici par morceaux, en s'arrêtant à 128 Kio ; si elle est plus longue, le flux
 * est remplacé en mémoire par ces 128 premiers Kio, non compressés. PDFBox n'utilisant jamais les octets
 * au-delà, le résultat est celui qu'il aurait obtenu avec une mémoire illimitée. Le fichier n'est pas modifié.
 */
final class CidToGidMapLimiter {

    /** Taille maximale utile d'une table : 65 536 CID × 2 octets */
    static final int MAX_DECODED_BYTES = 0x10000 * 2;

    /** Taille maximale d'un résultat intermédiaire quand plusieurs filtres s'enchaînent */
    private static final int MAX_INTERMEDIATE_BYTES = 16 << 20;

    private CidToGidMapLimiter() {
    }

    /**
     * @param font dictionnaire d'une police (simple ou composite)
     * @return true si une table /CIDToGIDMap trop longue a été tronquée
     */
    static boolean limit(COSDictionary font) {
        if (font == null || !COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE))) {
            return false;
        }
        COSArray descendants = font.getCOSArray(COSName.DESCENDANT_FONTS);
        if (descendants == null) {
            return false;
        }
        boolean truncated = false;
        for (int i = 0; i < descendants.size(); i++) {
            if (descendants.getObject(i) instanceof COSDictionary descendant
                    && descendant.getDictionaryObject(COSName.CID_TO_GID_MAP) instanceof COSStream map) {
                truncated |= truncate(map);
            }
        }
        return truncated;
    }

    private static boolean truncate(COSStream map) {
        byte[] prefix;
        try {
            prefix = decodePrefix(map);
        } catch (IOException e) {
            // flux illisible : PDFBox rencontrera la même erreur et la traitera comme d'habitude
            return false;
        }
        if (prefix == null) {
            return false;
        }
        map.removeItem(COSName.FILTER);
        map.removeItem(COSName.DECODE_PARMS);
        try (OutputStream output = map.createOutputStream()) {
            output.write(prefix);
        } catch (IOException e) {
            throw new IllegalStateException("Écriture en mémoire impossible", e);
        }
        return true;
    }

    /**
     * @return les octets décodés, au plus {@link #MAX_DECODED_BYTES}, si le flux a dû être tronqué ; null sinon
     */
    private static byte[] decodePrefix(COSStream stream) throws IOException {
        List<Filter> filters = filters(stream.getFilters());
        InputStream input = stream.createRawInputStream();
        boolean overflowed = false;
        try {
            for (int i = 0; ; i++) {
                boolean last = i >= filters.size() - 1;
                BoundedOutputStream output = new BoundedOutputStream(last ? MAX_DECODED_BYTES : MAX_INTERMEDIATE_BYTES);
                try {
                    if (filters.isEmpty()) {
                        input.transferTo(output);
                    } else {
                        filters.get(i).decode(input, output, stream, i, DecodeOptions.DEFAULT);
                    }
                } catch (LimitReached e) {
                    // assez d'octets décodés : la suite du flux est ignorée
                }
                input.close();
                overflowed |= output.overflowed;
                if (last) {
                    return overflowed ? output.toByteArray() : null;
                }
                input = new ByteArrayInputStream(output.toByteArray());
            }
        } finally {
            input.close();
        }
    }

    private static List<Filter> filters(COSBase names) throws IOException {
        List<Filter> filters = new ArrayList<>();
        if (names instanceof COSName name) {
            filters.add(FilterFactory.INSTANCE.getFilter(name));
        } else if (names instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (!(array.getObject(i) instanceof COSName name)) {
                    throw new IOException("Filtre invalide : " + array.getObject(i));
                }
                filters.add(FilterFactory.INSTANCE.getFilter(name));
            }
        }
        return filters;
    }

    /**
     * Garde au plus {@code limit} octets, puis interrompt le décodage dès qu'un octet de plus arrive
     */
    private static final class BoundedOutputStream extends ByteArrayOutputStream {
        private final int limit;
        private boolean overflowed;

        BoundedOutputStream(int limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void write(int b) {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int room = limit - count;
            if (len > room) {
                super.write(b, off, room);
                overflowed = true;
                throw new LimitReached();
            }
            super.write(b, off, len);
        }
    }

    /**
     * Non vérifiée, pour traverser les filtres PDFBox qui ne laissent passer que les IOException qu'ils produisent
     */
    private static final class LimitReached extends RuntimeException {
        LimitReached() {
            super(null, null, false, false);
        }
    }
}
