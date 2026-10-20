package fr.theses.batch.util.parser;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CidToGidMapLimiterTest {

    /**
     * Table identité pour les 65 536 CID possibles, suivie de octets parasites jusqu'à la taille donnée
     */
    private static byte[] identityMapPaddedTo(int size) {
        byte[] map = new byte[size];
        Arrays.fill(map, (byte) 0xFF);
        for (int cid = 0; cid < 0x10000 && 2 * cid + 1 < size; cid++) {
            map[2 * cid] = (byte) (cid >> 8);
            map[2 * cid + 1] = (byte) cid;
        }
        return map;
    }

    private static COSStream flateStream(byte[] decoded) throws IOException {
        COSStream stream = new COSStream();
        try (OutputStream output = stream.createOutputStream(COSName.FLATE_DECODE)) {
            output.write(decoded);
        }
        return stream;
    }

    private static COSDictionary type0FontWith(COSStream cidToGidMap) {
        COSDictionary descendant = new COSDictionary();
        descendant.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
        if (cidToGidMap != null) {
            descendant.setItem(COSName.CID_TO_GID_MAP, cidToGidMap);
        }
        COSArray descendants = new COSArray();
        descendants.add(descendant);
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE0);
        font.setItem(COSName.DESCENDANT_FONTS, descendants);
        return font;
    }

    private static byte[] decoded(COSStream stream) throws IOException {
        try (InputStream input = stream.createInputStream()) {
            return input.readAllBytes();
        }
    }

    @Test
    void tableTropGrande_tronqueeAuxCidPossibles() throws IOException {
        byte[] map = identityMapPaddedTo(8 * CidToGidMapLimiter.MAX_DECODED_BYTES);
        COSStream stream = flateStream(map);

        assertThat(CidToGidMapLimiter.limit(type0FontWith(stream))).isTrue();

        assertThat(stream.getFilters()).isNull();
        assertThat(decoded(stream)).isEqualTo(Arrays.copyOf(map, CidToGidMapLimiter.MAX_DECODED_BYTES));
    }

    @Test
    void tableDeTailleMaximale_inchangee() throws IOException {
        byte[] map = identityMapPaddedTo(CidToGidMapLimiter.MAX_DECODED_BYTES);
        COSStream stream = flateStream(map);

        assertThat(CidToGidMapLimiter.limit(type0FontWith(stream))).isFalse();

        assertThat(stream.getFilters()).isEqualTo(COSName.FLATE_DECODE);
        assertThat(decoded(stream)).isEqualTo(map);
    }

    @Test
    void tableIdentityOuAbsente_rienAFaire() {
        COSDictionary identity = type0FontWith(null);
        ((COSDictionary) identity.getCOSArray(COSName.DESCENDANT_FONTS).getObject(0))
                .setItem(COSName.CID_TO_GID_MAP, COSName.IDENTITY);

        assertThat(CidToGidMapLimiter.limit(identity)).isFalse();
        assertThat(CidToGidMapLimiter.limit(type0FontWith(null))).isFalse();
    }

    @Test
    void policeSimpleOuDictionnaireIncomplet_rienAFaire() {
        COSDictionary simple = new COSDictionary();
        simple.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
        COSDictionary withoutDescendants = new COSDictionary();
        withoutDescendants.setItem(COSName.SUBTYPE, COSName.TYPE0);

        assertThat(CidToGidMapLimiter.limit(simple)).isFalse();
        assertThat(CidToGidMapLimiter.limit(withoutDescendants)).isFalse();
        assertThat(CidToGidMapLimiter.limit(null)).isFalse();
    }

    @Test
    void fluxIllisible_rienAFaire() throws IOException {
        COSStream stream = new COSStream();
        try (OutputStream output = stream.createRawOutputStream()) {
            output.write("pas du zlib".getBytes());
        }
        stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);

        assertThat(CidToGidMapLimiter.limit(type0FontWith(stream))).isFalse();
    }
}
